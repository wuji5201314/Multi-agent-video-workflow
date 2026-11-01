package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ContentMaterialTaskCreateReq {
    @NotNull
    @Min(1)
    private Integer contentRevision;
}
