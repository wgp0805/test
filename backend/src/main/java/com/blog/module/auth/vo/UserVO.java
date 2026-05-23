package com.blog.module.auth.vo;

public record UserVO(
        Long id,
        String username,
        String nickname,
        String avatar,
        String email,
        String bio
) {}
