package com.blog.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blog.module.user.entity.UserEntity;
import com.blog.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminInitializer {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public ApplicationRunner ensureAdmin() {
        return args -> {
            UserEntity exists = userMapper.selectOne(
                    Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, "admin"));
            if (exists != null) {
                return;
            }
            UserEntity admin = new UserEntity();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("作者");
            admin.setEmail("admin@example.com");
            admin.setBio("默认作者，请尽快修改密码");
            admin.setCreatedAt(OffsetDateTime.now());
            admin.setUpdatedAt(OffsetDateTime.now());
            userMapper.insert(admin);
            log.warn("已创建默认管理员 admin / admin123，请尽快修改密码");
        };
    }
}
