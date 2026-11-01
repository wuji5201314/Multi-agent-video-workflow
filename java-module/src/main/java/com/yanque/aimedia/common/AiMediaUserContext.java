package com.yanque.aimedia.common;

import com.yanque.common.pojo.LoginInfo;
import com.yanque.common.utils.AuthThreadlocal;

/**
 * AI 媒体模块当前用户上下文。
 * <p>
 * 源项目通过 {@code UserContext.getUserId()} 取登录人；目标项目把登录信息
 * 存在 {@link AuthThreadlocal} 中，这里做等价读取。
 */
public final class AiMediaUserContext {

    private AiMediaUserContext() {
    }

    /** 当前登录用户 ID；未登录或登录信息不完整时返回 null。 */
    public static Long getUserId() {
        LoginInfo info = AuthThreadlocal.getLoginInfo();
        return info == null || info.getUserDetailRes() == null
                ? null : info.getUserDetailRes().getId();
    }
}
