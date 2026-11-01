package com.yanque.aimedia.pojo.vo.resvo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class PresignUploadRes {
    private String uploadUrl;
    private String objectKey;
    private Long expires;
    private Map<String, String> headers;
}
