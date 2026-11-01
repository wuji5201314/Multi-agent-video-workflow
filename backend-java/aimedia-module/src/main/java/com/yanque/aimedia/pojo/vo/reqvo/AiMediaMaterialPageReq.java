package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 素材库分页筛选条件。 */
@Data
public class AiMediaMaterialPageReq {
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 12;

    /** 同时检索素材标题、摘要、核心观点、标签和来源视频标题。 */
    private String keyword;
    /** 素材类型，例如 VIEWPOINT、Q_AND_A。 */
    private String clipType;
    /** 说话人角色，例如 HOST、CALLER_1。 */
    private String speaker;
}
