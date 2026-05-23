package com.blog.common;

public final class ErrorCode {
    public static final int OK = 0;
    public static final int PARAM_INVALID = 1400;
    public static final int UNAUTHORIZED = 1401;
    public static final int FORBIDDEN = 1403;
    public static final int NOT_FOUND = 1404;
    public static final int RATE_LIMIT = 1429;

    public static final int LOGIN_FAILED = 1001;

    public static final int SYSTEM_ERROR = 9500;

    private ErrorCode() {}
}
