package com.blog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtil {

    private final SecretKey key;
    private final long expireSeconds;
    private final String issuer;

    public JwtUtil(String secret, long expireSeconds, String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
        this.issuer = issuer;
    }

    public String issue(Long uid, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("uid", uid)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireSeconds * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public TokenClaims parse(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long uid = c.get("uid", Long.class);
        return new TokenClaims(uid, c.getSubject(), c.getExpiration());
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    public record TokenClaims(Long uid, String username, Date expiresAt) {}
}
