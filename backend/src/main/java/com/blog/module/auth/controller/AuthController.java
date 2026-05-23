package com.blog.module.auth.controller;

import com.blog.common.Result;
import com.blog.module.auth.dto.LoginRequest;
import com.blog.module.auth.service.AuthService;
import com.blog.module.auth.vo.LoginResponse;
import com.blog.module.auth.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @GetMapping("/me")
    public Result<UserVO> me(Authentication authentication) {
        return Result.ok(authService.me(authentication.getName()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
