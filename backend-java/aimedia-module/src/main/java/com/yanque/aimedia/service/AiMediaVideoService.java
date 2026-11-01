package com.yanque.aimedia.service;

import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoCreateReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPageReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPresignReq;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoRes;

public interface AiMediaVideoService {
    PageResult<AiMediaVideoRes> page(AiMediaVideoPageReq req);

    PresignUploadRes presignUpload(AiMediaVideoPresignReq req);

    AiMediaVideoCreateRes create(AiMediaVideoCreateReq req);

    PresignDownloadRes previewUrl(Long id);
}
