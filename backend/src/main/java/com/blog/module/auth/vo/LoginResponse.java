package com.blog.module.auth.vo;

public record LoginResponse(
        String token,
        long expiresIn,
        UserVO user
) {}
