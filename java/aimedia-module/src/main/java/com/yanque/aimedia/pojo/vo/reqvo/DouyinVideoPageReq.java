package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 抖音视频分页查询参数。 */
@Data
public class DouyinVideoPageReq {
    /** 当前页码，从 1 开始。 */
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    /** 每页条数，最多返回 100 条。 */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    /** 可选模糊搜索词，匹配标题、描述、作者昵称和命中关键词。 */
    private String keyword;
}
