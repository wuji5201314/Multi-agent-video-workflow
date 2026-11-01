package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.enums.MediaInputMode;
import com.yanque.aimedia.enums.MediaProcessStatus;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.mapper.AiMediaAudioMapper;
import com.yanque.aimedia.mapper.AiMediaProcessStepMapper;
import com.yanque.aimedia.mapper.AiMediaProcessTaskMapper;
import com.yanque.aimedia.mapper.AiMediaTranscriptMapper;
import com.yanque.aimedia.mapper.AiMediaVideoMapper;
import com.yanque.aimedia.mapper.AiMediaVideoClipMapper;
import com.yanque.aimedia.pojo.entity.AiMediaAudioEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理任务持久化服务。
 *
 * 这里集中维护任务、步骤和处理产物的一致性；具体业务编排放在 Biz 层。
 */
@Service
public class AiMediaProcessTaskService {
    private static final int MAX_ERROR_LENGTH = 1000;

    private final AiMediaVideoMapper videoMapper;
    private final AiMediaProcessTaskMapper taskMapper;
    private final AiMediaProcessStepMapper stepMapper;
    private final AiMediaAudioMapper audioMapper;
    private final AiMediaTranscriptMapper transcriptMapper;
    private final AiMediaVideoClipMapper videoClipMapper;

    public AiMediaProcessTaskService(AiMediaVideoMapper videoMapper,
                                     AiMediaProcessTaskMapper taskMapper,
                                     AiMediaProcessStepMapper stepMapper,
                                     AiMediaAudioMapper audioMapper,
                                     AiMediaTranscriptMapper transcriptMapper,
                                     AiMediaVideoClipMapper videoClipMapper) {
        this.videoMapper = videoMapper;
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.audioMapper = audioMapper;
        this.transcriptMapper = transcriptMapper;
        this.videoClipMapper = videoClipMapper;
    }

    /** 创建任务，同时按 Java 中定义的顺序生成步骤实例。 */
    @Transactional
    public AiMediaProcessTaskEntity create(Long videoId, Long userId) {
        // 原视频必须已经通过上传登记接口写入 ai_media_video。
        AiMediaVideoEntity video = videoMapper.selectById(videoId);
        if (video == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }
        // 视频表只记录上传状态，不再借用它表示离线处理状态。
        if (!"UPLOADED".equals(video.getStatus())) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_CONFLICT,
                    "原始视频尚未上传完成");
        }
        if (taskMapper.selectLatestByVideoId(videoId) != null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_CONFLICT,
                    "该视频已经创建处理任务");
        }

        AiMediaProcessTaskEntity task = new AiMediaProcessTaskEntity();
        task.setVideoId(videoId);
        task.setStatus(MediaProcessStatus.PENDING.name());
        task.setCurrentStep(MediaProcessStepCode.PREPARE_VIDEO.name());
        task.setInputMode(MediaInputMode.DIRECT_URL.name());
        task.setCreatedBy(userId);
        try {
            // task.video_id 唯一键是真正的并发保护，避免两个请求重复创建任务。
            if (taskMapper.insert(task) != 1) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_OPERATION_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_CONFLICT,
                    "该视频已经创建处理任务");
        }

        // 把枚举中的步骤定义转换为数据库步骤实例。
        // 任务执行时读取的是这些实例，而不是临时在内存中拼装步骤。
        for (MediaProcessStepCode stepCode : MediaProcessStepCode.initialSteps()) {
            AiMediaProcessStepEntity step = new AiMediaProcessStepEntity();
            step.setTaskId(task.getId());
            step.setStepCode(stepCode.name());
            step.setStepOrder(stepCode.getOrder());
            step.setStatus(MediaProcessStatus.PENDING.name());
            step.setRetryCount(0);
            if (stepMapper.insert(step) != 1) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED);
            }
        }
        return task;
    }

    /** 查询任务，不存在时统一抛出业务异常。 */
    public AiMediaProcessTaskEntity requireTask(Long taskId) {
        AiMediaProcessTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_NOT_FOUND);
        }
        return task;
    }

    /** 查询任务关联的原始视频。 */
    public AiMediaVideoEntity requireVideo(Long videoId) {
        AiMediaVideoEntity video = videoMapper.selectById(videoId);
        if (video == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }
        return video;
    }

    /** 查询任务已经生成的音频产物。 */
    public AiMediaAudioEntity requireAudio(Long taskId) {
        AiMediaAudioEntity audio = audioMapper.selectByTaskId(taskId);
        if (audio == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_AUDIO_NOT_FOUND);
        }
        return audio;
    }

    /** 查询 ASR_SUBMIT 保存的转写任务记录。 */
    public AiMediaTranscriptEntity requireTranscript(Long taskId) {
        AiMediaTranscriptEntity transcript = transcriptMapper.selectByTaskId(taskId);
        if (transcript == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_TRANSCRIPT_NOT_FOUND);
        }
        return transcript;
    }

    /** 按 step_order 查询当前任务的第一条 PENDING 步骤。 */
    public AiMediaProcessStepEntity nextPending(Long taskId) {
        return stepMapper.selectNextPending(taskId);
    }

    /** 原子领取任务，防止同一个 taskId 被两个线程同时执行。 */
    public boolean claim(Long taskId, String firstStep) {
        return taskMapper.claim(taskId, firstStep) == 1;
    }

    /** 查询已经达到轮询间隔的 ASR_QUERY 步骤。 */
    public List<AiMediaProcessStepEntity> waitingAsrQueries(LocalDateTime updatedBefore, int limit) {
        return stepMapper.selectWaitingAsrQueries(updatedBefore, limit);
    }

    /** 原子领取 WAITING 步骤，避免多实例重复查询同一个火山任务。 */
    public boolean claimWaiting(AiMediaProcessStepEntity step) {
        return stepMapper.claimWaiting(step.getId()) == 1;
    }

    /**
     * 为人工重试恢复数据库状态。
     * 只把 current_step 对应的失败步骤改回 PENDING，之前的 SUCCESS 步骤保持不变。
     */
    @Transactional
    public AiMediaProcessTaskEntity prepareRetry(Long videoId) {
        AiMediaProcessTaskEntity task = taskMapper.selectLatestByVideoId(videoId);
        if (task == null || !MediaProcessStatus.FAILED.name().equals(task.getStatus())) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_CONFLICT,
                    "当前视频没有可重试的失败任务");
        }

        AiMediaProcessStepEntity failedStep = stepMapper.selectByTaskIdAndCode(
                task.getId(), task.getCurrentStep());
        if (failedStep == null
                || stepMapper.prepareRetry(failedStep.getId()) != 1
                || taskMapper.prepareRetry(task.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_CONFLICT,
                    "失败任务状态已变化，请刷新后重试");
        }

        // 转写表只保存产物；失败步骤恢复后直接复用已有 provider_task_id 和 ASR 结果。
        return task;
    }

    /**
     * 开始执行一个步骤。
     * 任务 current_step 和步骤 PROCESSING 状态在同一事务内更新。
     */
    @Transactional
    public void startStep(Long taskId, AiMediaProcessStepEntity step) {
        if (taskMapper.updateCurrentStep(taskId, step.getStepCode()) != 1
                || stepMapper.markProcessing(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED,
                    "步骤状态已经变化，不能重复执行");
        }
    }

    /** 完成普通步骤，例如 PREPARE_VIDEO。 */
    @Transactional
    public void completeStep(AiMediaProcessStepEntity step) {
        if (stepMapper.markSuccess(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED);
        }
    }

    /** 音频记录与 EXTRACT_AUDIO 成功状态必须在同一个事务中提交。 */
    @Transactional
    public void completeAudioStep(AiMediaProcessTaskEntity task,
                                  AiMediaProcessStepEntity step,
                                  AiMediaAudioEntity audio) {
        if (audioMapper.insert(audio) != 1 || stepMapper.markSuccess(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED);
        }

        AiMediaProcessStepEntity next = stepMapper.selectNextPending(task.getId());
        if (next != null) {
            // 预留后续 ASR 步骤：如果还有 PENDING 节点，任务继续保持 PROCESSING。
            taskMapper.updateCurrentStep(task.getId(), next.getStepCode());
            return;
        }

        // 当前阶段没有后续节点，只结束处理任务。
        if (taskMapper.markSuccess(task.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_OPERATION_FAILED);
        }
    }

    /**
     * 完成 ASR_SUBMIT，并把 ASR_QUERY 改为 WAITING。
     * 提交线程到此结束，数据库任务继续保持 PROCESSING。
     */
    @Transactional
    public void completeAsrSubmit(AiMediaProcessTaskEntity task,
                                  AiMediaProcessStepEntity submitStep,
                                  AiMediaAudioEntity audio,
                                  String providerTaskId) {
        AiMediaTranscriptEntity transcript = new AiMediaTranscriptEntity();
        transcript.setVideoId(task.getVideoId());
        transcript.setTaskId(task.getId());
        transcript.setAudioId(audio.getId());
        transcript.setProvider("VOLCENGINE");
        transcript.setProviderTaskId(providerTaskId);
        transcript.setLanguage("zh-CN");
        transcript.setDurationMs(audio.getDurationMs());

        AiMediaProcessStepEntity queryStep = stepMapper.selectByTaskIdAndCode(
                task.getId(), MediaProcessStepCode.ASR_QUERY.name());
        if (queryStep == null
                || transcriptMapper.insert(transcript) != 1
                || stepMapper.markSuccess(submitStep.getId()) != 1
                || stepMapper.markPendingAsWaiting(queryStep.getId()) != 1
                || taskMapper.updateCurrentStep(task.getId(), MediaProcessStepCode.ASR_QUERY.name()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_ASR_OPERATION_FAILED);
        }
    }

    /** 火山仍在处理时恢复 WAITING，并以 updated_at 记录本次查询时间。 */
    @Transactional
    public void keepAsrWaiting(AiMediaProcessStepEntity queryStep) {
        if (stepMapper.markProcessingAsWaiting(queryStep.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED);
        }
    }

    /** 保存 ASR 结果并完成 ASR_QUERY，任务继续进入转写整理节点。 */
    @Transactional
    public void completeAsrQuery(AiMediaProcessTaskEntity task,
                                 AiMediaProcessStepEntity queryStep,
                                 String fullText,
                                 String utterancesJson) {
        if (transcriptMapper.saveAsrResult(task.getId(), fullText, utterancesJson) != 1
                || stepMapper.markSuccess(queryStep.getId()) != 1
                || taskMapper.updateCurrentStep(
                        task.getId(), MediaProcessStepCode.TRANSCRIPT_REFINEMENT.name()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_ASR_OPERATION_FAILED);
        }
    }

    /** 保存大模型整理结果，并把任务推进到素材片段生成节点。 */
    @Transactional
    public void completeTranscriptRefinement(AiMediaProcessTaskEntity task,
                                             AiMediaProcessStepEntity refinementStep,
                                             String fullText,
                                             String utterancesJson) {
        if (transcriptMapper.saveRefinedResult(task.getId(), fullText, utterancesJson) != 1
                || stepMapper.markSuccess(refinementStep.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_TRANSCRIPT_REFINEMENT_FAILED);
        }
        advanceOrCompleteTask(task.getId());
    }

    /** 最终切片方案与 MATERIAL_SEGMENT_GENERATION 成功状态在同一事务提交。 */
    @Transactional
    public void completeMaterialSegmentGeneration(AiMediaProcessTaskEntity task,
                                                  AiMediaProcessStepEntity step,
                                                  List<AiMediaVideoClipEntity> clips) {
        // 方案生成步骤失败重试时，以本次最终选择覆盖旧方案，避免留下重复片段。
        videoClipMapper.deleteByTaskId(task.getId());
        for (AiMediaVideoClipEntity clip : clips) {
            if (videoClipMapper.insert(clip) != 1) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_SEGMENT_GENERATION_FAILED);
            }
        }
        if (stepMapper.markSuccess(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_SEGMENT_GENERATION_FAILED);
        }
        advanceOrCompleteTask(task.getId());
    }

    /** 查询当前任务已经确定的素材方案。 */
    public List<AiMediaVideoClipEntity> listVideoClips(Long taskId) {
        return videoClipMapper.selectByTaskId(taskId);
    }

    /** 单条文件上传完成后立即保存，节点失败重试时无需重复处理成功片段。 */
    @Transactional
    public void saveRenderedVideoClip(Long clipId, String objectKey, long fileSize) {
        if (videoClipMapper.saveRenderedFile(clipId, objectKey, fileSize) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_CLIP_RENDER_FAILED,
                    "视频素材文件信息保存失败");
        }
    }

    /** 所有片段均已上传后完成 MATERIAL_CLIP_RENDER，并继续后续节点。 */
    @Transactional
    public void completeMaterialClipRender(AiMediaProcessTaskEntity task,
                                           AiMediaProcessStepEntity step) {
        if (stepMapper.markSuccess(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_CLIP_RENDER_FAILED);
        }
        advanceOrCompleteTask(task.getId());
    }

    /** 完成 MATERIAL_INDEX；当前版本中它是最后一个处理步骤。 */
    @Transactional
    public void completeMaterialIndex(AiMediaProcessTaskEntity task,
                                      AiMediaProcessStepEntity step) {
        if (stepMapper.markSuccess(step.getId()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_INDEX_FAILED);
        }
        advanceOrCompleteTask(task.getId());
    }

    /** 失败后只记录当前节点，不调度自动重试。 */
    @Transactional
    public void fail(AiMediaProcessTaskEntity task,
                     AiMediaProcessStepEntity step,
                     Throwable throwable) {
        String errorMessage = normalizeError(throwable);

        // 步骤和任务一起记录失败，原始视频的上传状态保持不变。
        // current_step 会保留失败节点编码，供后续手动重试使用。
        stepMapper.markFailed(step.getId(), errorMessage);
        taskMapper.markFailed(task.getId(), step.getStepCode());
    }

    /**
     * 回收失联步骤。
     * <p>
     * 进程在执行中途退出时，步骤会永久停在 PROCESSING，而 prepareRetry 只认 FAILED，
     * 任务因此再也无法被任何入口恢复。这里把它落成一次普通失败，让既有的手动重试重新可达。
     * <p>
     * 先对步骤做 CAS：若执行线程其实刚刚完成，markFailed 影响行数为 0，此时绝不能
     * 再去改任务状态，否则会把一个已经成功的任务判死。
     */
    @Transactional
    public boolean failStaleStep(AiMediaProcessStepEntity step, String errorMessage) {
        if (stepMapper.markFailed(step.getId(), errorMessage) != 1) {
            return false;
        }
        // 任务已经不是 PROCESSING 时返回 0，属于并发收尾，不作为异常处理。
        taskMapper.markFailed(step.getTaskId(), step.getStepCode());
        return true;
    }

    /** 查询指定步骤编码下已经失联的步骤。 */
    public List<AiMediaProcessStepEntity> staleProcessingSteps(String stepCode,
                                                               LocalDateTime staleBefore,
                                                               int limit) {
        return stepMapper.selectStaleProcessing(stepCode, staleBefore, limit);
    }

    /**
     * ASR 查询遇到瞬时故障时退回 WAITING 并累计重试次数。
     * 复用 updated_at 作为下次查询时钟，退避由调度器按 retry_count 计算，不需要新增字段。
     */
    @Transactional
    public void deferAsrQuery(AiMediaProcessStepEntity step, String errorMessage) {
        String message = errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
        if (stepMapper.markWaitingForRetry(step.getId(), message) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED,
                    "ASR 查询步骤状态已变化，放弃本次退避");
        }
    }

    private String normalizeError(Throwable throwable) {
        // 数据库字段最大 1000 字符，提前截断避免保存错误时再次抛异常。
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    /** 当前步骤完成后进入下一条 PENDING；不存在后续步骤时才结束整个任务。 */
    private void advanceOrCompleteTask(Long taskId) {
        AiMediaProcessStepEntity next = stepMapper.selectNextPending(taskId);
        int affected = next == null
                ? taskMapper.markSuccess(taskId)
                : taskMapper.updateCurrentStep(taskId, next.getStepCode());
        if (affected != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_TASK_OPERATION_FAILED);
        }
    }
}
