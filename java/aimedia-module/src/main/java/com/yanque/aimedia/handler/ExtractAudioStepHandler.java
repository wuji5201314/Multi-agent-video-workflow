package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaAudioEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import com.yanque.aimedia.service.AiMediaFfmpegService;
import com.yanque.aimedia.service.AiMediaObjectStorageService;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** EXTRACT_AUDIO 步骤：FFmpeg 提取音频、上传 TOS 并保存音频记录。 */
@Component
public class ExtractAudioStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaFfmpegService ffmpegService;
    private final AiMediaObjectStorageService objectStorageService;

    public ExtractAudioStepHandler(AiMediaProcessTaskService taskService,
                                   AiMediaFfmpegService ffmpegService,
                                   AiMediaObjectStorageService objectStorageService) {
        this.taskService = taskService;
        this.ffmpegService = ffmpegService;
        this.objectStorageService = objectStorageService;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.EXTRACT_AUDIO;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        AiMediaVideoEntity video = taskService.requireVideo(task.getVideoId());

        // 下载地址有效期较短，所以在真正调用 FFmpeg 前重新生成。
        String inputUrl = objectStorageService.createVideoInputUrl(video.getObjectKey());

        // FFmpeg 直接读取 TOS URL，只在服务器临时目录生成一个 MP3 文件。
        AiMediaFfmpegService.ExtractedAudio extracted = ffmpegService.extractMp3(inputUrl, task.getId());
        Path localAudio = extracted.file();
        try {
            // 每个任务使用独立路径；同一任务手动重试时仍复用该 objectKey。
            String objectKey = buildAudioObjectKey(task);

            // 先上传 TOS，并通过 HeadObject 校验上传后的文件大小。
            objectStorageService.uploadAudio(localAudio, objectKey, extracted.fileSize());

            // TOS 上传成功后，组装音频业务记录。
            AiMediaAudioEntity audio = buildAudioEntity(task, video, objectKey, extracted.fileSize());

            // 音频记录和当前步骤成功状态在同一事务中更新，任务继续执行后续节点。
            taskService.completeAudioStep(task, step, audio);
        } finally {
            // 无论上传或落库是否成功，都不在应用服务器长期保留中间文件。
            ffmpegService.deleteQuietly(localAudio);
        }
    }

    /** 组装需要写入 ai_media_audio 的音频元数据。 */
    private AiMediaAudioEntity buildAudioEntity(AiMediaProcessTaskEntity task,
                                                AiMediaVideoEntity video,
                                                String objectKey,
                                                long fileSize) {
        AiMediaAudioEntity audio = new AiMediaAudioEntity();
        audio.setVideoId(task.getVideoId());
        audio.setTaskId(task.getId());
        audio.setObjectKey(objectKey);
        audio.setFileFormat("mp3");
        audio.setContentType("audio/mpeg");
        audio.setFileSize(fileSize);
        audio.setDurationMs(video.getDurationMs());
        audio.setSampleRate(16000);
        audio.setChannels(1);
        return audio;
    }

    /** 生成当前任务的音频 TOS 对象路径。 */
    private String buildAudioObjectKey(AiMediaProcessTaskEntity task) {
        return "ai/media/audio/" + task.getVideoId() + "/" + task.getId() + "/audio-v1.mp3";
    }
}
