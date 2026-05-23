package com.blog.module.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.module.auth.dto.LoginRequest;
import com.blog.module.auth.vo.LoginResponse;
import com.blog.module.auth.vo.UserVO;
import com.blog.module.user.entity.UserEntity;
import com.blog.module.user.mapper.UserMapper;
import com.blog.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest req) {
        UserEntity u = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, req.username()));
        if (u == null || !passwordEncoder.matches(req.password(), u.getPassword())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        String token = jwtUtil.issue(u.getId(), u.getUsername());
        return new LoginResponse(token, jwtUtil.getExpireSeconds(), toVO(u));
    }

    public UserVO me(String username) {
        UserEntity u = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
        if (u == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return toVO(u);
    }

    private UserVO toVO(UserEntity u) {
        return new UserVO(u.getId(), u.getUsername(), u.getNickname(),
                u.getAvatar(), u.getEmail(), u.getBio());
    }
}
