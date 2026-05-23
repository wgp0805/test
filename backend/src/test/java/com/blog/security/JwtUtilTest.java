package com.blog.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private final JwtUtil jwt = new JwtUtil(
            "dev-secret-please-change-in-prod-0123456789abcdef",
            7200L,
            "blog");

    @Test
    void issued_token_can_be_parsed() {
        String token = jwt.issue(42L, "admin");
        var claims = jwt.parse(token);
        assertEquals("admin", claims.username());
        assertEquals(42L, claims.uid());
    }

    @Test
    void tampered_token_fails() {
        String token = jwt.issue(1L, "admin");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> jwt.parse(tampered));
    }

    @Test
    void expired_token_throws_expired() {
        JwtUtil shortLived = new JwtUtil(
                "dev-secret-please-change-in-prod-0123456789abcdef",
                -1L,
                "blog");
        String token = shortLived.issue(1L, "admin");
        assertThrows(ExpiredJwtException.class, () -> shortLived.parse(token));
    }
}
