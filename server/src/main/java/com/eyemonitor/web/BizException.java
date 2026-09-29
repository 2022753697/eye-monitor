package com.eyemonitor.web;

/**
 * 业务异常：携带业务码与提示，由 GlobalExceptionHandler 转为 {code,data,msg}。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}