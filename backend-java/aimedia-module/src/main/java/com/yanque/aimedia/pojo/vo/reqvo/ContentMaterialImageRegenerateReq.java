package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 人工针对一张图片填写的重做意见。 */
@Data
public class ContentMaterialImageRegenerateReq {
    @NotBlank
    @Size(max = 1000)
    private String feedback;
}
