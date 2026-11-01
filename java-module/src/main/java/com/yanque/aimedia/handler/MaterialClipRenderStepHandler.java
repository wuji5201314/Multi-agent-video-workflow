package com.yanque.aimedia.handler;

import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import com.yanque.aimedia.service.AiMediaFfmpegService;
import com.yanque.aimedia.service.AiMediaObjectStorageService;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** MATERIAL_CLIP_RENDER：按方案精确裁切视频并上传 TOS。 */
@Component
public class MaterialClipRenderStepHandler implements AiMediaProcessStepHandler {
    private static final Logger log = LoggerFactory.getLogger(MaterialClipRenderStepHandler.class);

    private final AiMediaProcessTaskService taskService;
    private final AiMediaFfmpegService ffmpegService;
    private final AiMediaObjectStorageService objectStorageService;

    public MaterialClipRenderStepHandler(AiMediaProcessTaskService taskService,
                                         AiMediaFfmpegService ffmpegService,
                                         AiMediaObjectStorageService objectStorageService) {
        this.taskService = taskService;
        this.ffmpegService = ffmpegService;
        this.objectStorageService = objectStorageService;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.MATERIAL_CLIP_RENDER;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        AiMediaVideoEntity video = taskService.requireVideo(task.getVideoId());
        List<AiMediaVideoClipEntity> clips = taskService.listVideoClips(task.getId());

        for (AiMediaVideoClipEntity clip : clips) {
            // 已上传的片段是本节点的断点；手动重试时直接跳过。
            if (StrUtil.isNotBlank(clip.getObjectKey())) {
                continue;
            }
            renderAndUpload(task, video, clip);
        }

        // 允许上一步宁缺毋滥返回 0 条素材，此时裁切节点直接成功。
        taskService.completeMaterialClipRender(task, step);
    }

    private void renderAndUpload(AiMediaProcessTaskEntity task,
                                 AiMediaVideoEntity video,
                                 AiMediaVideoClipEntity clip) {
        log.info("开始裁切视频素材: taskId={}, clipId={}, segmentNo={}, startMs={}, endMs={}",
                task.getId(), clip.getId(), clip.getSegmentNo(), clip.getStartMs(), clip.getEndMs());

        // 每条素材开始前重新签名，避免前一条转码耗时过长导致 URL 过期。
        String inputUrl = objectStorageService.createVideoInputUrl(video.getObjectKey());
        AiMediaFfmpegService.RenderedVideo rendered = ffmpegService.renderVideoClip(
                inputUrl, task.getId(), clip.getSegmentNo(), clip.getStartMs(), clip.getEndMs());
        Path localVideo = rendered.file();
        try {
            String objectKey = buildObjectKey(task, clip);
            objectStorageService.uploadVideoClip(localVideo, objectKey, rendered.fileSize());
            taskService.saveRenderedVideoClip(clip.getId(), objectKey, rendered.fileSize());
            log.info("视频素材裁切并上传完成: taskId={}, clipId={}, objectKey={}, fileSize={}",
                    task.getId(), clip.getId(), objectKey, rendered.fileSize());
        } finally {
            // 上传成功、上传失败或数据库更新失败，都立即清理本地临时视频。
            ffmpegService.deleteQuietly(localVideo);
        }
    }

    private String buildObjectKey(AiMediaProcessTaskEntity task, AiMediaVideoClipEntity clip) {
        return "ai/media/clips/" + task.getVideoId() + "/" + task.getId()
                + "/segment-" + clip.getSegmentNo() + ".mp4";
    }
}
