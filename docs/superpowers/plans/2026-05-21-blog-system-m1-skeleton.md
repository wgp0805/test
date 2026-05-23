# 博客系统 M1 骨架实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 Monorepo 仓库骨架，跑通 PG → Flyway → 后端 JWT 登录 → 前端 Vue 登录页跳通 → 拿到 token → 命中 `/api/auth/me` 的全链路。

**Architecture:** 单仓 Monorepo（`backend/` Spring Boot 3.3 + `frontend/` Vite + Vue 3）。后端用 MyBatis-Plus 操作 PostgreSQL 16，Flyway 管理迁移，Spring Security + JJWT 实现无状态鉴权。前端用 Pinia 持久化 token，Axios 拦截器统一处理 `code` 协议响应，路由守卫保护 `/admin/**`。

**Tech Stack:**
- 后端：Spring Boot 3.3、Java 17、Maven、MyBatis-Plus 3.5.7、PostgreSQL 16、Flyway 10、Spring Security 6、JJWT 0.12、Lombok
- 前端：Vue 3.4、Vite 5、TypeScript 5、Pinia 2、Vue Router 4、Element Plus、UnoCSS、Axios、pnpm
- 基础设施：Docker Compose（本地 PG）
- 包名 `com.blog`；JWT 2h 无 refresh；主色 `#3b82f6`

**约束（不要重复确认）：** 默认作者 `admin / admin123`（BCrypt 加密），由后端 `AdminInitializer` 在启动时若不存在则创建（避开把 BCrypt 串硬编码到 Flyway SQL）。

---

## Phase A：仓库基础设施

### Task A1：补全 `.gitignore`，创建 backend/ 与 frontend/ 空骨架

**Files:**
- Modify: `.gitignore`
- Create: `backend/.gitkeep`
- Create: `frontend/.gitkeep`

- [ ] **Step 1：在仓库根目录覆盖 `.gitignore`**

把现有 `.gitignore` 替换为下面内容（含设计文档已有的 `.idea/` 等忽略项 + 后端/前端/数据卷忽略项）：

```gitignore
# IDE
.idea/
.vscode/
*.iml
*.swp

# OS
.DS_Store
Thumbs.db

# 后端
backend/target/
backend/uploads/
backend/logs/
backend/.mvn/wrapper/maven-wrapper.jar

# 前端
frontend/node_modules/
frontend/dist/
frontend/.vite/
frontend/pnpm-lock.yaml.bak

# 本地 Docker 数据卷
.data/

# 日志
*.log

# 环境变量
.env
.env.local
!.env.example
```

- [ ] **Step 2：创建 backend/ 和 frontend/ 占位文件**

```bash
mkdir -p backend frontend
touch backend/.gitkeep frontend/.gitkeep
```

- [ ] **Step 3：验证状态**

Run: `git status`
Expected: 看到 `.gitignore` 被 modify、`backend/.gitkeep`、`frontend/.gitkeep` 是 untracked。

- [ ] **Step 4：commit**

```bash
git add .gitignore backend/.gitkeep frontend/.gitkeep
git commit -m "chore: 初始化 Monorepo 目录骨架与 .gitignore"
```

---

### Task A2：docker-compose.yml 启动 PostgreSQL 16

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1：创建 `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16
    container_name: blog-postgres
    environment:
      POSTGRES_DB: blog
      POSTGRES_USER: blog
      POSTGRES_PASSWORD: blog123
      TZ: Asia/Shanghai
    ports:
      - "5432:5432"
    volumes:
      - ./.data/pg:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U blog -d blog"]
      interval: 5s
      timeout: 3s
      retries: 10
```

- [ ] **Step 2：启动并等待健康检查**

Run:
```bash
docker compose up -d postgres
docker compose ps
```
Expected: `blog-postgres` 状态为 `Up ... (healthy)`。若仍是 `(health: starting)`，等 10 秒再 `docker compose ps`。

- [ ] **Step 3：连接验证**

Run:
```bash
docker exec -it blog-postgres psql -U blog -d blog -c "select version();"
```
Expected: 打印 `PostgreSQL 16.x ...`。

- [ ] **Step 4：commit**

```bash
git add docker-compose.yml
git commit -m "chore: 添加 docker-compose 启动 PostgreSQL 16"
```

---

### Task A3：Maven Wrapper + 后端 `pom.xml`

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/mvnw`、`backend/mvnw.cmd`（由 `mvn wrapper:wrapper` 生成）

- [ ] **Step 1：在 backend/ 下生成 Maven Wrapper**

如果本机有 mvn：
```bash
cd backend
mvn -N io.takari:maven:wrapper -Dmaven=3.9.9
```
没有本机 mvn：从已有 Spring 项目复制 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` 到 `backend/`，并把 wrapper properties 内容改为：
```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
```

- [ ] **Step 2：写 `backend/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.blog</groupId>
    <artifactId>blog</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>blog</name>
    <description>个人博客后端</description>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <jjwt.version>0.12.6</jjwt.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>

        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>blog</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3：拉取依赖验证**

Run:
```bash
cd backend
./mvnw -q -DskipTests dependency:resolve
```
Expected: 无错误退出。首次会下载较多依赖。

- [ ] **Step 4：commit**

```bash
git add backend/pom.xml backend/mvnw backend/mvnw.cmd backend/.mvn
git commit -m "chore(backend): 引入 Maven Wrapper 与 Spring Boot 3.3 依赖"
```

---

## Phase B：后端骨架

### Task B1：`BlogApplication` 启动类 + `application.yml`

**Files:**
- Create: `backend/src/main/java/com/blog/BlogApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-dev.yml`

- [ ] **Step 1：写启动类**

`backend/src/main/java/com/blog/BlogApplication.java`：

```java
package com.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.blog.module.**.mapper")
public class BlogApplication {
    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }
}
```

- [ ] **Step 2：写 `application.yml`**

```yaml
spring:
  profiles:
    active: dev
  application:
    name: blog
  jackson:
    time-zone: Asia/Shanghai
    date-format: yyyy-MM-dd HH:mm:ss

server:
  port: 8080
  servlet:
    context-path: /

blog:
  jwt:
    # 生产用环境变量覆盖；开发用固定串便于测试；HS256 要求 ≥ 32 字节
    secret: ${JWT_SECRET:dev-secret-please-change-in-prod-0123456789abcdef}
    expire-seconds: 7200
    issuer: blog
```

- [ ] **Step 3：写 `application-dev.yml`**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/blog
    username: blog
    password: blog123
    driver-class-name: org.postgresql.Driver

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: true
      logic-not-delete-value: false

logging:
  level:
    com.blog: DEBUG
    org.springframework.security: INFO
```

- [ ] **Step 4：commit（暂不运行，下个 Task 加完 Flyway 再启动）**

```bash
git add backend/src/main/java/com/blog/BlogApplication.java backend/src/main/resources
git commit -m "feat(backend): 添加启动类与 dev 配置"
```

---

### Task B2：Flyway `V1__init_user.sql`（user 表）

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init_user.sql`

- [ ] **Step 1：写迁移 SQL**

```sql
-- 作者表（M1 仅 user，文章/分类/标签/评论由后续里程碑添加）
CREATE TABLE IF NOT EXISTS "user" (
  id         BIGSERIAL PRIMARY KEY,
  username   VARCHAR(50)  UNIQUE NOT NULL,
  password   VARCHAR(100) NOT NULL,
  nickname   VARCHAR(50)  NOT NULL,
  avatar     VARCHAR(255),
  email      VARCHAR(100),
  bio        VARCHAR(500),
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
```

- [ ] **Step 2：启动应用，让 Flyway 跑迁移**

Run:
```bash
cd backend
./mvnw -q spring-boot:run
```
Expected：日志中出现 `Flyway Community Edition ... by Redgate`、`Successfully applied 1 migration to schema "public", now at version v1`，端口 8080 listen。

- [ ] **Step 3：验证表存在**

另开终端：
```bash
docker exec -it blog-postgres psql -U blog -d blog -c '\d "user"'
```
Expected：列出 id/username/password/...

`Ctrl+C` 停掉 spring-boot。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/resources/db/migration/V1__init_user.sql
git commit -m "feat(backend): Flyway V1 创建 user 表"
```

---

### Task B3：`Result<T>` 统一响应 + `ErrorCode`

**Files:**
- Create: `backend/src/main/java/com/blog/common/Result.java`
- Create: `backend/src/main/java/com/blog/common/ErrorCode.java`

- [ ] **Step 1：写 `ErrorCode.java`**

```java
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
```

- [ ] **Step 2：写 `Result.java`**

```java
package com.blog.common;

import lombok.Getter;

@Getter
public class Result<T> {
    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.OK, "ok", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
```

- [ ] **Step 3：commit**

```bash
git add backend/src/main/java/com/blog/common
git commit -m "feat(backend): 添加 Result 统一响应与 ErrorCode"
```

---

### Task B4：`BusinessException` + `GlobalExceptionHandler`

**Files:**
- Create: `backend/src/main/java/com/blog/common/BusinessException.java`
- Create: `backend/src/main/java/com/blog/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/blog/common/GlobalExceptionHandlerTest.java`

- [ ] **Step 1：先写测试 `GlobalExceptionHandlerTest.java`**

```java
package com.blog.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/business")
        public String boom() {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        @GetMapping("/probe/unknown")
        public String crash() {
            throw new RuntimeException("boom");
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    @Test
    void business_exception_returns_business_code() throws Exception {
        mvc.perform(get("/probe/business").accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(1001))
           .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void unknown_exception_returns_9500() throws Exception {
        mvc.perform(get("/probe/unknown").accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(9500));
    }
}
```

- [ ] **Step 2：运行测试，确认编译失败（类不存在）**

Run: `cd backend && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test`
Expected：编译错误，提示 `BusinessException` 与 `GlobalExceptionHandler` 不存在。

- [ ] **Step 3：写 `BusinessException.java`**

```java
package com.blog.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

- [ ] **Step 4：写 `GlobalExceptionHandler.java`**

```java
package com.blog.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数错误");
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> handleAuth(AuthenticationException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleForbidden(AccessDeniedException e) {
        return Result.fail(ErrorCode.FORBIDDEN, "无权限");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
    }
}
```

- [ ] **Step 5：跑测试通过**

Run: `cd backend && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test`
Expected：BUILD SUCCESS，两个用例通过。

- [ ] **Step 6：commit**

```bash
git add backend/src/main/java/com/blog/common backend/src/test/java/com/blog/common
git commit -m "feat(backend): 添加 BusinessException 与全局异常处理"
```

---

### Task B5：`UserEntity` + `UserMapper`

**Files:**
- Create: `backend/src/main/java/com/blog/module/user/entity/UserEntity.java`
- Create: `backend/src/main/java/com/blog/module/user/mapper/UserMapper.java`

- [ ] **Step 1：写 `UserEntity.java`**

注意表名是 `user`（PG 关键字），需 `@TableName(value="\"user\"")` 加双引号转义。

```java
package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "\"user\"")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String email;
    private String bio;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 2：写 `UserMapper.java`**

```java
package com.blog.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.user.entity.UserEntity;

public interface UserMapper extends BaseMapper<UserEntity> {
}
```

- [ ] **Step 3：编译验证**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected：BUILD SUCCESS。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/module/user
git commit -m "feat(backend): 添加 UserEntity 与 UserMapper"
```

---

## Phase C：Security + JWT

### Task C1：`JwtUtil` + 单元测试

**Files:**
- Create: `backend/src/main/java/com/blog/security/JwtUtil.java`
- Test: `backend/src/test/java/com/blog/security/JwtUtilTest.java`

`JwtUtil` 负责 issue/parse Token，载荷 `{ uid, username, exp }`，HS256，2 小时。

- [ ] **Step 1：先写测试 `JwtUtilTest.java`**

```java
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
```

- [ ] **Step 2：跑测试，确认不通过**

Run: `cd backend && ./mvnw -q -Dtest=JwtUtilTest test`
Expected：编译错误，`JwtUtil` 不存在。

- [ ] **Step 3：写 `JwtUtil.java`**

```java
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
```

- [ ] **Step 4：把 `JwtUtil` 注册为 Bean**

新增 `backend/src/main/java/com/blog/security/JwtConfig.java`：

```java
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
```

- [ ] **Step 5：跑测试通过**

Run: `cd backend && ./mvnw -q -Dtest=JwtUtilTest test`
Expected：BUILD SUCCESS，三个用例通过。

- [ ] **Step 6：commit**

```bash
git add backend/src/main/java/com/blog/security backend/src/test/java/com/blog/security
git commit -m "feat(backend): 添加 JwtUtil 与 HS256 issue/parse 测试"
```

---

### Task C2：`CustomUserDetailsService`

**Files:**
- Create: `backend/src/main/java/com/blog/security/CustomUserDetailsService.java`

- [ ] **Step 1：写实现**

```java
package com.blog.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blog.module.user.entity.UserEntity;
import com.blog.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity u = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
        if (u == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return User.withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities(AuthorityUtils.NO_AUTHORITIES)
                .build();
    }
}
```

- [ ] **Step 2：编译验证**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected：BUILD SUCCESS。

- [ ] **Step 3：commit**

```bash
git add backend/src/main/java/com/blog/security/CustomUserDetailsService.java
git commit -m "feat(backend): 添加 CustomUserDetailsService"
```

---

### Task C3：`JwtAuthenticationFilter` + `JwtAuthenticationEntryPoint`

**Files:**
- Create: `backend/src/main/java/com/blog/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/blog/security/JwtAuthenticationEntryPoint.java`

- [ ] **Step 1：写 `JwtAuthenticationEntryPoint.java`**

```java
package com.blog.security;

import com.blog.common.ErrorCode;
import com.blog.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(),
                Result.fail(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));
    }
}
```

- [ ] **Step 2：写 `JwtAuthenticationFilter.java`**

```java
package com.blog.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                JwtUtil.TokenClaims claims = jwtUtil.parse(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        claims.username(),
                        null,
                        AuthorityUtils.NO_AUTHORITIES);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                log.debug("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 3：编译**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected：BUILD SUCCESS。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/security
git commit -m "feat(backend): 添加 JWT 过滤器与 401 入口"
```

---

### Task C4：`SecurityConfig`（含 CORS、密码编码器、AuthenticationManager）

**Files:**
- Create: `backend/src/main/java/com/blog/config/SecurityConfig.java`

- [ ] **Step 1：写 `SecurityConfig.java`**

```java
package com.blog.config;

import com.blog.security.JwtAuthenticationEntryPoint;
import com.blog.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:5173"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/admin/**", "/api/auth/me", "/api/auth/logout").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 2：启动应用，验证 Security 链路接入**

Run: `cd backend && ./mvnw -q spring-boot:run`
Expected：日志含 `Will secure ... [/**] with filters: ...`，无报错；端口 8080 listen。`Ctrl+C` 退出。

- [ ] **Step 3：commit**

```bash
git add backend/src/main/java/com/blog/config/SecurityConfig.java
git commit -m "feat(backend): 添加 SecurityConfig（CORS + JWT 过滤链）"
```

---

## Phase D：Auth Module（登录闭环）

### Task D1：登录相关 DTO 与 VO

**Files:**
- Create: `backend/src/main/java/com/blog/module/auth/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/blog/module/auth/vo/LoginResponse.java`
- Create: `backend/src/main/java/com/blog/module/auth/vo/UserVO.java`

- [ ] **Step 1：`LoginRequest.java`**

```java
package com.blog.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @NotBlank @Size(min = 6, max = 100) String password
) {}
```

- [ ] **Step 2：`UserVO.java`**

```java
package com.blog.module.auth.vo;

public record UserVO(
        Long id,
        String username,
        String nickname,
        String avatar,
        String email,
        String bio
) {}
```

- [ ] **Step 3：`LoginResponse.java`**

```java
package com.blog.module.auth.vo;

public record LoginResponse(
        String token,
        long expiresIn,
        UserVO user
) {}
```

- [ ] **Step 4：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/module/auth
git commit -m "feat(backend): 添加 auth 模块 DTO/VO"
```

---

### Task D2：`AuthService` + `AdminInitializer`（启动时确保 admin 存在）

**Files:**
- Create: `backend/src/main/java/com/blog/module/auth/service/AuthService.java`
- Create: `backend/src/main/java/com/blog/module/auth/AdminInitializer.java`

`AdminInitializer` 在启动时检查 `user` 表里是否有 `admin`，若没有则 `BCryptPasswordEncoder.encode("admin123")` 后插入。这样避开把 BCrypt 串硬写进 Flyway SQL。

- [ ] **Step 1：写 `AuthService.java`**

```java
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
```

- [ ] **Step 2：写 `AdminInitializer.java`**

```java
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
```

- [ ] **Step 3：启动应用，验证 admin 被插入**

Run: `cd backend && ./mvnw -q spring-boot:run`
Expected：日志包含 `已创建默认管理员 admin / admin123`。

另开终端：
```bash
docker exec -it blog-postgres psql -U blog -d blog -c "select id, username, nickname from \"user\";"
```
Expected：1 行 `admin / 作者`。

`Ctrl+C` 退出。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/module/auth
git commit -m "feat(backend): 添加 AuthService 与 AdminInitializer"
```

---

### Task D3：`AuthController` + 集成测试

**Files:**
- Create: `backend/src/main/java/com/blog/module/auth/controller/AuthController.java`
- Test: `backend/src/test/java/com/blog/module/auth/controller/AuthControllerTest.java`

- [ ] **Step 1：写 `AuthController.java`**

```java
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
```

- [ ] **Step 2：写集成测试 `AuthControllerTest.java`**

依赖本地 PG（由 docker compose 起）。使用 `@SpringBootTest` + `MockMvc`，让 Flyway 跑迁移、`AdminInitializer` 插入 admin。

```java
package com.blog.module.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ObjectMapper mapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test @Order(1)
    void login_with_wrong_password_returns_business_error() throws Exception {
        mvc().perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1001));
    }

    @Test @Order(2)
    void login_with_correct_password_returns_token() throws Exception {
        mvc().perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.user.username").value("admin"));
    }

    @Test @Order(3)
    void me_without_token_returns_1401() throws Exception {
        mvc().perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1401));
    }

    @Test @Order(4)
    void me_with_token_returns_admin() throws Exception {
        MvcResult login = mvc().perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andReturn();
        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        String token = body.path("data").path("token").asText();

        mvc().perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value("admin"));
    }
}
```

- [ ] **Step 3：确认 PG 在跑，执行集成测试**

```bash
docker compose up -d postgres
cd backend && ./mvnw -q -Dtest=AuthControllerTest test
```
Expected：BUILD SUCCESS，4 个用例全部通过。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/module/auth/controller backend/src/test
git commit -m "feat(backend): 完成 /api/auth login/me/logout 与集成测试"
```

---

## Phase E：前端骨架

### Task E1：`package.json` + Vite + TS 基础

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/tsconfig.node.json`
- Create: `frontend/index.html`
- Create: `frontend/.env.development`
- Create: `frontend/env.d.ts`

- [ ] **Step 1：`package.json`**

```json
{
  "name": "blog-frontend",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "axios": "^1.7.7",
    "element-plus": "^2.8.4",
    "pinia": "^2.2.4",
    "pinia-plugin-persistedstate": "^4.1.1",
    "vue": "^3.4.38",
    "vue-router": "^4.4.5"
  },
  "devDependencies": {
    "@iconify-json/ep": "^1.2.1",
    "@types/node": "^22.7.5",
    "@vitejs/plugin-vue": "^5.1.4",
    "typescript": "~5.5.4",
    "unocss": "^0.62.4",
    "unplugin-auto-import": "^0.18.3",
    "unplugin-vue-components": "^0.27.4",
    "vite": "^5.4.8",
    "vue-tsc": "^2.1.6"
  }
}
```

- [ ] **Step 2：`vite.config.ts`**

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import path from 'node:path'

export default defineConfig({
  plugins: [
    vue(),
    UnoCSS(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
```

- [ ] **Step 3：`tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "jsx": "preserve",
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "skipLibCheck": true,
    "noEmit": true,
    "allowImportingTsExtensions": true,
    "types": ["vite/client", "node"],
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src/**/*", "env.d.ts"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 4：`tsconfig.node.json`**

```json
{
  "compilerOptions": {
    "composite": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "allowSyntheticDefaultImports": true,
    "skipLibCheck": true,
    "types": ["node"]
  },
  "include": ["vite.config.ts", "uno.config.ts"]
}
```

- [ ] **Step 5：`env.d.ts`**

```ts
/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

- [ ] **Step 6：`index.html`**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>个人博客</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 7：`.env.development`**

```
VITE_API_BASE=/api
```

- [ ] **Step 8：安装依赖**

```bash
cd frontend
pnpm install
```
Expected：依赖装好，无 ERR。

- [ ] **Step 9：commit**

```bash
git add frontend/package.json frontend/vite.config.ts frontend/tsconfig.json frontend/tsconfig.node.json frontend/index.html frontend/.env.development frontend/env.d.ts
git commit -m "chore(frontend): 初始化 Vite + TS + Element Plus 自动导入"
```

> 注意：`pnpm-lock.yaml` 是否提交按团队习惯；个人项目建议提交，单独 commit 即可。

---

### Task E2：UnoCSS + `main.ts` + 全局样式

**Files:**
- Create: `frontend/uno.config.ts`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/styles/main.css`

- [ ] **Step 1：`uno.config.ts`**

```ts
import { defineConfig, presetUno, presetAttributify, presetIcons, presetTypography } from 'unocss'

export default defineConfig({
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({ scale: 1.2 }),
    presetTypography(),
  ],
  theme: {
    colors: {
      primary: '#3b82f6',
    },
  },
})
```

- [ ] **Step 2：`src/styles/main.css`**

```css
:root {
  --color-primary: #3b82f6;
  --el-color-primary: #3b82f6;
}

html, body, #app {
  height: 100%;
  margin: 0;
  font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif;
  color: #1f2937;
  background: #f8fafc;
}

a { color: var(--color-primary); text-decoration: none; }
```

- [ ] **Step 3：`src/App.vue`**

```vue
<template>
  <RouterView />
</template>

<script setup lang="ts"></script>
```

- [ ] **Step 4：`src/main.ts`**

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPersistedState from 'pinia-plugin-persistedstate'
import 'virtual:uno.css'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()
pinia.use(piniaPersistedState)
app.use(pinia)
app.use(router)
app.mount('#app')
```

- [ ] **Step 5：commit**

```bash
git add frontend/uno.config.ts frontend/src/main.ts frontend/src/App.vue frontend/src/styles
git commit -m "feat(frontend): 接入 UnoCSS、Element Plus 与全局样式"
```

---

### Task E3：Axios 请求封装 + `api/auth.ts` + 类型定义

**Files:**
- Create: `frontend/src/types/index.ts`
- Create: `frontend/src/api/request.ts`
- Create: `frontend/src/api/auth.ts`

- [ ] **Step 1：`src/types/index.ts`**

```ts
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface User {
  id: number
  username: string
  nickname: string
  avatar?: string
  email?: string
  bio?: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: User
}
```

- [ ] **Step 2：`src/api/request.ts`**

实现拦截器：请求自动塞 token；响应按 `code` 协议解包；1401 清 token + 跳登录。

```ts
import axios, { type AxiosResponse } from 'axios'
import { ElMessage, ElNotification } from 'element-plus'
import type { ApiResult } from '@/types'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('blog_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response: AxiosResponse<ApiResult<unknown>>) => {
    const body = response.data
    if (body.code === 0) {
      return body.data as never
    }
    if (body.code === 1401) {
      localStorage.removeItem('blog_token')
      localStorage.removeItem('blog_user')
      ElMessage.warning('登录已过期，请重新登录')
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.href = `/admin/login?redirect=${redirect}`
      return Promise.reject(body)
    }
    if (body.code === 1429) {
      ElMessage.warning(body.message || '操作过于频繁')
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(body)
  },
  (error) => {
    ElNotification.error({ title: '网络错误', message: '系统繁忙，请稍后重试' })
    return Promise.reject(error)
  },
)

export default request
```

> 注意：拦截器直接返回 `body.data`，使用方按 `await login(...)` 取到的就是已解包的 `data`。返回类型用 `as never` 让调用端的泛型生效（在 `auth.ts` 里 `request.post<unknown, LoginResponse>(...)` 这种用法可以工作）。

- [ ] **Step 3：`src/api/auth.ts`**

```ts
import request from './request'
import type { LoginResponse, User } from '@/types'

export interface LoginPayload {
  username: string
  password: string
}

export function login(payload: LoginPayload) {
  return request.post<unknown, LoginResponse>('/auth/login', payload)
}

export function getMe() {
  return request.get<unknown, User>('/auth/me')
}

export function logout() {
  return request.post<unknown, void>('/auth/logout')
}
```

- [ ] **Step 4：类型检查**

```bash
cd frontend && pnpm exec vue-tsc -b --noEmit
```
Expected：无错误（首次会编译，可能需要 10~30 秒）。

- [ ] **Step 5：commit**

```bash
git add frontend/src/types frontend/src/api
git commit -m "feat(frontend): 添加 axios 拦截器与 auth API"
```

---

### Task E4：Pinia `userStore`（含持久化）

**Files:**
- Create: `frontend/src/stores/user.ts`

- [ ] **Step 1：写 `user.ts`**

```ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, getMe, logout as apiLogout, type LoginPayload } from '@/api/auth'
import type { User } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>('')
  const user = ref<User | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  async function login(payload: LoginPayload) {
    const res = await apiLogin(payload)
    token.value = res.token
    user.value = res.user
    localStorage.setItem('blog_token', res.token)
  }

  async function fetchMe() {
    const me = await getMe()
    user.value = me
    return me
  }

  async function logout() {
    try {
      await apiLogout()
    } catch {
      /* 后端无状态，失败也忽略 */
    }
    token.value = ''
    user.value = null
    localStorage.removeItem('blog_token')
    localStorage.removeItem('blog_user')
  }

  return { token, user, isLoggedIn, login, fetchMe, logout }
}, {
  persist: {
    key: 'blog_user',
    pick: ['token', 'user'],
  },
})
```

> token 在 `localStorage` 里有两份：拦截器读 `blog_token`（不依赖 Pinia hydration 顺序），Pinia 持久化整个 store 到 `blog_user`。`login` 时两边都写，`logout` 时两边都清。

- [ ] **Step 2：类型检查**

```bash
cd frontend && pnpm exec vue-tsc -b --noEmit
```
Expected：通过。

- [ ] **Step 3：commit**

```bash
git add frontend/src/stores
git commit -m "feat(frontend): 添加 userStore 与 localStorage 持久化"
```

---

### Task E5：路由 + 守卫 + 占位 Layout/Dashboard

**Files:**
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/layouts/AdminLayout.vue`
- Create: `frontend/src/views/admin/DashboardView.vue`
- Create: `frontend/src/views/HomeView.vue`

- [ ] **Step 1：`src/views/HomeView.vue`（前台占位）**

```vue
<template>
  <div class="p-8 text-center">
    <h1 class="text-3xl font-bold mb-4">个人博客</h1>
    <p class="text-gray-600">M1 骨架已就绪，业务页面随后续里程碑添加。</p>
    <RouterLink to="/admin/login" class="text-primary">前往后台登录</RouterLink>
  </div>
</template>

<script setup lang="ts"></script>
```

- [ ] **Step 2：`src/layouts/AdminLayout.vue`（后台占位 Layout）**

```vue
<template>
  <el-container class="h-full">
    <el-header class="flex items-center justify-between bg-white shadow-sm px-6">
      <span class="font-semibold text-lg">博客后台</span>
      <div class="flex items-center gap-3">
        <span class="text-gray-600">{{ user?.nickname || user?.username }}</span>
        <el-button size="small" @click="handleLogout">退出</el-button>
      </div>
    </el-header>
    <el-main class="bg-gray-50">
      <RouterView />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const { user } = storeToRefs(userStore)

async function handleLogout() {
  await userStore.logout()
  router.push('/admin/login')
}
</script>
```

- [ ] **Step 3：`src/views/admin/DashboardView.vue`（占位）**

```vue
<template>
  <div class="bg-white rounded p-6">
    <h2 class="text-xl font-semibold mb-2">欢迎回来，{{ user?.nickname || user?.username }}</h2>
    <p class="text-gray-600">M1 骨架已跑通。文章/分类/评论等模块将由 M2~M4 添加。</p>
  </div>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/user'

const { user } = storeToRefs(useUserStore())
</script>
```

- [ ] **Step 4：`src/router/index.ts`**

```ts
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  { path: '/', component: () => import('@/views/HomeView.vue') },
  { path: '/admin/login', component: () => import('@/views/admin/LoginView.vue') },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('@/views/admin/DashboardView.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.token) {
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/admin/login' && userStore.token) {
    return { path: '/admin/dashboard' }
  }
})

export default router
```

- [ ] **Step 5：类型检查（LoginView.vue 尚不存在会报 import 找不到，先建空文件占位让本步只检查路由本身）**

新建空占位 `frontend/src/views/admin/LoginView.vue`：
```vue
<template><div>login placeholder</div></template>
<script setup lang="ts"></script>
```

```bash
cd frontend && pnpm exec vue-tsc -b --noEmit
```
Expected：通过。

- [ ] **Step 6：commit**

```bash
git add frontend/src/router frontend/src/layouts frontend/src/views
git commit -m "feat(frontend): 添加路由、守卫与后台占位页面"
```

---

### Task E6：`LoginView.vue` 登录页

**Files:**
- Modify: `frontend/src/views/admin/LoginView.vue`（覆盖上一 Task 的占位）

- [ ] **Step 1：完整覆盖 `LoginView.vue`**

```vue
<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50">
    <el-card class="w-96" shadow="always">
      <template #header>
        <div class="text-center text-xl font-semibold">博客后台登录</div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
            placeholder="admin123"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="w-full"
          :loading="loading"
          @click="handleLogin"
        >登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/admin/dashboard'
    router.replace(redirect)
  } catch {
    /* 拦截器已 toast 错误信息 */
  } finally {
    loading.value = false
  }
}
</script>
```

- [ ] **Step 2：类型检查**

```bash
cd frontend && pnpm exec vue-tsc -b --noEmit
```
Expected：通过。

- [ ] **Step 3：commit**

```bash
git add frontend/src/views/admin/LoginView.vue
git commit -m "feat(frontend): 实现后台登录页（含校验与 redirect 回跳）"
```

---

## Phase F：端到端联调与收尾

### Task F1：端到端跑通登录链路

**Files:** 无新增文件，纯人工/浏览器验证。

前置：保持 PG 容器在跑（`docker compose ps` 看到 healthy）。

- [ ] **Step 1：启动后端**

终端 1：
```bash
cd backend && ./mvnw -q spring-boot:run
```
Expected：日志含 `Started BlogApplication`、若是首次启动还会看到 `已创建默认管理员 admin / admin123`。

- [ ] **Step 2：启动前端**

终端 2：
```bash
cd frontend && pnpm dev
```
Expected：Vite 输出 `Local: http://localhost:5173/`。

- [ ] **Step 3：浏览器验收**

打开 http://localhost:5173/admin/login

依次验证：
- 输入 `admin / wrong-password` → 提示"用户名或密码错误"，仍停留在登录页
- 输入 `admin / admin123` → toast "登录成功"，跳到 `/admin/dashboard`，标题栏显示昵称"作者"
- 打开 DevTools → Application → Local Storage：存在 `blog_token` 与 `blog_user`
- DevTools Network 查看 `/api/auth/me` 请求头携带 `Authorization: Bearer ...`
- 点击右上角"退出" → 回到登录页，`blog_token`/`blog_user` 被清空
- 手动访问 http://localhost:5173/admin/dashboard （未登录态）→ 自动跳 `/admin/login?redirect=%2Fadmin%2Fdashboard`，登录后回到 dashboard
- 在 DevTools 里手动把 `blog_token` 改成乱码刷新页面 → 命中 1401 后清 token 跳回登录

Expected：以上 7 个场景全部通过。

- [ ] **Step 4：可选——验证防 CORS 报错**

DevTools Console 应无任何 CORS error。若 Vite 代理走不通可临时直连 `http://localhost:8080/api/auth/login` 看 `Access-Control-Allow-Origin` 头。

- [ ] **Step 5：停服**

终端 1/2 各 `Ctrl+C`。

> 注意：本 Task 不产出代码，但**它的通过是 M1 验收门槛**。任何场景失败请回到对应 Task 修复。

---

### Task F2：收尾 — README + Push

**Files:**
- Create: `README.md`

- [ ] **Step 1：写 `README.md`**

```markdown
# blog

个人博客（Spring Boot 3.3 + Vue 3 + PostgreSQL 16），单作者，前后端分离。

## 当前进度

M1 骨架（仓库初始化 + JWT 登录闭环）已完成。后续里程碑见 `docs/superpowers/specs/`。

## 本地启动

```bash
# 1. 启动 PostgreSQL
docker compose up -d postgres

# 2. 启动后端（端口 8080）
cd backend
./mvnw spring-boot:run

# 3. 启动前端（端口 5173）
cd frontend
pnpm install
pnpm dev
```

打开 http://localhost:5173/admin/login ，默认账号 `admin / admin123`。

## 目录

- `backend/` — Spring Boot 3.3 + MyBatis-Plus + Flyway
- `frontend/` — Vite 5 + Vue 3 + TypeScript + Element Plus + UnoCSS
- `docs/superpowers/specs/` — 设计文档
- `docs/superpowers/plans/` — 实现计划

## 文档

- 设计：[`docs/superpowers/specs/2026-05-21-blog-system-design.md`](docs/superpowers/specs/2026-05-21-blog-system-design.md)
- M1 实现计划：[`docs/superpowers/plans/2026-05-21-blog-system-m1-skeleton.md`](docs/superpowers/plans/2026-05-21-blog-system-m1-skeleton.md)
```

- [ ] **Step 2：跑一次完整测试**

```bash
docker compose up -d postgres
cd backend && ./mvnw -q test
```
Expected：BUILD SUCCESS。

- [ ] **Step 3：commit README**

```bash
git add README.md
git commit -m "docs: 添加 README 与本地启动说明"
```

- [ ] **Step 4：push**

```bash
git push origin main
```
Expected：成功推送到 https://github.com/wgp0805/test.git 。

- [ ] **Step 5：在 GitHub 网页确认提交历史完整、文档可读、CI 若有则通过**

---

## M1 验收清单

完成后应满足：

- [x] `docker compose up -d postgres` 可起 PG 16，healthcheck 通过
- [x] `cd backend && ./mvnw spring-boot:run` 启动后 Flyway 自动建 `user` 表，`AdminInitializer` 自动创建 `admin / admin123`
- [x] `./mvnw test` 全绿（包含 JwtUtilTest、GlobalExceptionHandlerTest、AuthControllerTest）
- [x] `cd frontend && pnpm dev` 启动 5173，登录页可用
- [x] 浏览器登录跳通：`admin/admin123` → 跳 dashboard，token 持久化，1401 自动跳回登录
- [x] Git history 干净，已 push 到远端 main


