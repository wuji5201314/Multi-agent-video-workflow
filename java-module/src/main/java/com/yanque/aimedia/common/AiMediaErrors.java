package com.yanque.aimedia.common;

import com.yanque.common.exception.BusinessException;

/**
 * AI 媒体模块异常工厂。
 * <p>
 * 目标项目的 {@link BusinessException} 使用预置常量，没有源项目的
 * {@code of(IErrorCode)} 形式，这里补齐等价工厂方法，保持模块内调用写法不变。
 */
public final class AiMediaErrors {

    private AiMediaErrors() {
    }

    public static BusinessException of(AiMediaErrorCode errorCode) {
        return new BusinessException(errorCode.getCode(), errorCode.getMessage());
    }

    public static BusinessException of(AiMediaErrorCode errorCode, String message) {
        return new BusinessException(errorCode.getCode(), message);
    }

    public static BusinessException of(Integer code, String message) {
        return new BusinessException(code, message);
    }
}
