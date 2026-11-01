package com.yanque.aimedia.common;

/**
 * AI 媒体模块错误码。
 * <p>
 * 错误码段位沿用源项目约定：TOS 22xxx、AI 媒体 33xxx、通用参数 40001。
 * <p>
 * 332xx / 333xx / 334xx 段与 YanQue-admin-14 保持一致的编码与语义，
 * 便于两端日志与前端错误提示对齐。
 */
public enum AiMediaErrorCode {

    SUCCESS(200, "操作成功"),

    PARAM_VALID_FAILED(40001, "请求参数校验失败"),

    TOS_OBJECT_KEY_INVALID(22001, "对象存储Key不合法"),
    TOS_PRESIGN_FAILED(22002, "生成对象存储预签名地址失败"),

    MEDIA_VIDEO_NOT_FOUND(33001, "原始视频不存在"),
    MEDIA_VIDEO_FILE_INVALID(33002, "视频文件不合法，仅支持 MP4、MOV、WebM 和 M4V"),
    MEDIA_VIDEO_FILE_TOO_LARGE(33003, "当前普通上传仅支持小于 5GiB 的视频"),
    MEDIA_VIDEO_OBJECT_KEY_INVALID(33004, "视频对象Key不合法"),
    MEDIA_VIDEO_OPERATION_FAILED(33005, "原始视频保存失败"),
    MEDIA_VIDEO_UPLOAD_NOT_COMPLETED(33006, "TOS 中未找到完整视频，请重新上传"),

    MEDIA_PROCESS_TASK_NOT_FOUND(33101, "视频处理任务不存在"),
    MEDIA_PROCESS_TASK_CONFLICT(33102, "该视频当前不能创建处理任务"),
    MEDIA_PROCESS_TASK_OPERATION_FAILED(33103, "视频处理任务操作失败"),
    MEDIA_PROCESS_STEP_OPERATION_FAILED(33104, "视频处理步骤操作失败"),
    MEDIA_AUDIO_EXTRACT_FAILED(33105, "视频音频提取失败"),
    MEDIA_AUDIO_UPLOAD_FAILED(33106, "音频上传 TOS 失败"),
    MEDIA_AUDIO_NOT_FOUND(33107, "该视频还没有生成音频"),
    MEDIA_TRANSCRIPT_NOT_FOUND(33108, "该任务还没有 ASR 转写记录"),
    MEDIA_ASR_OPERATION_FAILED(33109, "火山 ASR 操作失败"),
    MEDIA_TRANSCRIPT_REFINEMENT_FAILED(33110, "视频转写整理失败"),
    MEDIA_MATERIAL_SEGMENT_GENERATION_FAILED(33111, "视频素材片段生成失败"),
    MEDIA_MATERIAL_CLIP_RENDER_FAILED(33112, "视频素材裁切失败"),
    MEDIA_MATERIAL_CLIP_UPLOAD_FAILED(33113, "视频素材上传 TOS 失败"),
    MEDIA_MATERIAL_NOT_FOUND(33114, "视频素材不存在"),
    MEDIA_MATERIAL_NOT_RENDERED(33115, "视频素材尚未生成视频文件"),
    MEDIA_MATERIAL_INDEX_FAILED(33116, "视频素材索引写入失败"),

    CONTENT_MATERIAL_TASK_NOT_FOUND(33201, "文章素材任务不存在"),
    CONTENT_MATERIAL_TASK_CONFLICT(33202, "文章素材任务当前不能执行"),
    CONTENT_MATERIAL_TASK_OPERATION_FAILED(33203, "文章素材任务操作失败"),

    CONTENT_REVIEW_TASK_NOT_FOUND(33301, "内容审核任务不存在"),
    CONTENT_REVIEW_TASK_CONFLICT(33302, "内容审核任务当前不能执行"),
    CONTENT_REVIEW_TASK_OPERATION_FAILED(33303, "内容审核任务操作失败"),

    CONTENT_PREVIEW_TASK_NOT_FOUND(33401, "内容成品任务不存在"),
    CONTENT_PREVIEW_TASK_CONFLICT(33402, "内容成品任务当前不能执行"),
    CONTENT_PREVIEW_TASK_OPERATION_FAILED(33403, "内容成品任务操作失败");

    private final int code;
    private final String message;

    AiMediaErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
