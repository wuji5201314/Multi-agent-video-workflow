package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次原始视频离线处理任务。 */
@Data
public class AiMediaProcessTaskEntity {
    /** 任务主键。 */
    private Long id;
    /** 本次处理的原始视频 ID。 */
    private Long videoId;
    /** PENDING、PROCESSING、SUCCESS 或 FAILED。 */
    private String status;
    /** 当前正在执行的节点；失败时保留失败节点。 */
    private String currentStep;
    /** 视频读取方式，当前使用 DIRECT_URL。 */
    private String inputMode;
    /** 创建任务的后台用户 ID。 */
    private Long createdBy;
    /** 后台线程正式领取任务的时间。 */
    private LocalDateTime startedAt;
    /** 任务成功或失败的结束时间。 */
    private LocalDateTime finishedAt;
    /** 数据创建时间。 */
    private LocalDateTime createdAt;
    /** 数据最后更新时间。 */
    private LocalDateTime updatedAt;
}
