package com.linlay.springaiagw.model.agw;

import java.util.Map;

public record ApiResponse<T>(
        int code,
        String msg,
        T data
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Map<String, Object>> failure(int code, String msg) {
        return new ApiResponse<>(code, msg, Map.of());
    }

    public static <T> ApiResponse<T> failure(int code, String msg, T data) {
        return new ApiResponse<>(code, msg, data);
    }
}
