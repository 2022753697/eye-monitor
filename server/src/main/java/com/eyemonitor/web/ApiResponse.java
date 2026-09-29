package com.eyemonitor.web;

/**
 * 统一响应包装：{code, data, msg}。code=0 成功。
 */
public class ApiResponse<T> {

    private int code;
    private T data;
    private String msg;

    public ApiResponse() {}

    public ApiResponse(int code, T data, String msg) {
        this.code = code;
        this.data = data;
        this.msg = msg;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "ok");
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, null, msg);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
}