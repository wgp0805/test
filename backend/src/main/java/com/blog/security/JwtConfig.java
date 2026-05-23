package com.blog.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(
            @Value("${blog.jwt.secret}") String secret,
            @Value("${blog.jwt.expire-seconds}") long expireSeconds,
            @Value("${blog.jwt.issuer}") String issuer) {
        return new JwtUtil(secret, expireSeconds, issuer);
    }
}
