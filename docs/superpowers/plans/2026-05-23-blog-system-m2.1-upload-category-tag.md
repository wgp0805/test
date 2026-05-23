# M2.1 实现计划：文件上传 + 分类 + 标签

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M1 骨架上加入文件上传（图片）+ 分类后台 CRUD + 公开标签云 + 后台标签 CRUD，前端 admin 提供分类/标签管理页面，为 M2.2 文章模块铺路（cover URL、categoryId、tagIds 全部到位）。

**Architecture:** 沿用 M1 的分层（Controller → Service → Mapper → Entity）和统一响应 `Result<T>`。新增 `upload` 模块（无表）+ `category`/`tag` 两个 module（各自 entity/mapper/service/controller）。前端在 AdminLayout 下加 sidebar 导航 + 三个管理页（categories/tags + 让 dashboard 链接到它们）。

**Tech Stack:**
- 后端：Spring Boot 3.3、MyBatis-Plus 3.5.7、Flyway 10（V2 迁移）、Tika 2.x（MIME 检测）
- 前端：复用 M1 的 Vue 3.4 + Element Plus + Pinia + Axios 栈
- 文件存储：本地 `backend/uploads/` 目录，URL 形如 `/uploads/2026/05/uuid.png`

**关键约束（不要重复确认）：**
- 单文件 ≤ 5MB，仅 `image/jpeg|png|webp|gif`
- 双重校验：扩展名白名单 + Tika MIME 嗅探（不依赖客户端传的 `Content-Type`）
- 文件落盘按 `yyyy/MM/` 分目录，UUID 重命名避免冲突 + 路径穿越
- `/uploads/**` 走 Spring 静态资源映射；CORS 已在 M1 SecurityConfig 配过
- 分类/标签 slug 用户手工填写，仅校验唯一 + 正则 `^[a-z0-9-]+$`，不做拼音转换
- 软删除：category/tag 都用全局 `deleted` 字段；M1 已在 `application-dev.yml` 配 `logic-delete-field: deleted`
- 分类有 `description` 和 `sort`；标签只有 `name`/`slug`
- 公开 `/api/categories` 返回的每个分类带 `articleCount`（M2.1 阶段还没文章表，先返回 0，M2.2 加文章后真实计数会自动生效）
- 公开 `/api/tags` 同理，先返 `useCount=0`

---

## Phase A：文件上传

### Task A1：依赖 + 配置项

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-dev.yml`

- [ ] **Step 1：pom 加 Tika 依赖**

在 `<dependencies>` 中追加（放在 jjwt-jackson 之后即可）：

```xml
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
            <version>2.9.2</version>
        </dependency>
```

- [ ] **Step 2：`application.yml` 加 servlet multipart 配置 + upload 配置**

把 `blog:` 节点改为：

```yaml
blog:
  jwt:
    secret: ${JWT_SECRET:dev-secret-please-change-in-prod-0123456789abcdef}
    expire-seconds: 7200
    issuer: blog
  upload:
    dir: ${UPLOAD_DIR:./uploads}
    url-prefix: /uploads
    max-size-bytes: 5242880
    allowed-mime: image/jpeg,image/png,image/webp,image/gif
    allowed-ext: jpg,jpeg,png,webp,gif
```

在 `spring:` 节点下加（与 `application` 同级）：

```yaml
spring:
  application:
    name: blog
  servlet:
    multipart:
      enabled: true
      max-file-size: 5MB
      max-request-size: 5MB
  jackson:
    time-zone: Asia/Shanghai
    date-format: yyyy-MM-dd HH:mm:ss
```

> 注意：原 `spring:` 块里已有 `application` 和 `jackson`，**保留并加上 `servlet.multipart`**，不要整体覆盖。

- [ ] **Step 3：拉依赖验证**

Run: `cd backend && ./mvnw -q -DskipTests dependency:resolve`
Expected：BUILD SUCCESS。

- [ ] **Step 4：commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml
git commit -m "feat(backend): 添加 Tika 与上传配置项"
```

---

### Task A2：`UploadProperties` + 静态资源映射

**Files:**
- Create: `backend/src/main/java/com/blog/upload/config/UploadProperties.java`
- Create: `backend/src/main/java/com/blog/upload/config/UploadWebConfig.java`

- [ ] **Step 1：写 `UploadProperties.java`**

```java
package com.blog.upload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "blog.upload")
public class UploadProperties {
    private String dir;
    private String urlPrefix;
    private long maxSizeBytes;
    private Set<String> allowedMime;
    private Set<String> allowedExt;
}
```

- [ ] **Step 2：写 `UploadWebConfig.java`**

```java
package com.blog.upload.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
@RequiredArgsConstructor
public class UploadWebConfig implements WebMvcConfigurer {

    private final UploadProperties props;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        File dir = new File(props.getDir()).getAbsoluteFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建上传目录: " + dir);
        }
        registry.addResourceHandler(props.getUrlPrefix() + "/**")
                .addResourceLocations("file:" + dir.getPath() + File.separator);
    }
}
```

- [ ] **Step 3：编译**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected：BUILD SUCCESS。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/upload
git commit -m "feat(backend): 添加上传配置属性与静态资源映射"
```

---

### Task A3：`SecurityConfig` 放行 `/uploads/**`

**Files:**
- Modify: `backend/src/main/java/com/blog/config/SecurityConfig.java`

`/uploads/**` 现在被 SecurityConfig 的 `anyRequest().permitAll()` 默认放行，但更明确写出来便于阅读。

- [ ] **Step 1：定位 `filterChain` 的 `authorizeHttpRequests` 段**

当前代码：
```java
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/admin/**", "/api/auth/me", "/api/auth/logout").authenticated()
                .anyRequest().permitAll())
```

改为：
```java
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                .requestMatchers("/api/admin/**", "/api/auth/me", "/api/auth/logout").authenticated()
                .anyRequest().permitAll())
```

- [ ] **Step 2：跑现有测试确认没破坏 M1**

Run: `cd backend && ./mvnw -q test`
Expected：9 个用例全过，BUILD SUCCESS。

- [ ] **Step 3：commit**

```bash
git add backend/src/main/java/com/blog/config/SecurityConfig.java
git commit -m "feat(backend): SecurityConfig 显式放行 /uploads/**"
```

---

### Task A4：错误码扩展

**Files:**
- Modify: `backend/src/main/java/com/blog/common/ErrorCode.java`

加上传/资源相关的业务码。

- [ ] **Step 1：在 `ErrorCode` 中追加常量（放在 `SYSTEM_ERROR` 之前）**

```java
    // 上传相关
    public static final int UPLOAD_FILE_EMPTY = 1010;
    public static final int UPLOAD_FILE_TOO_LARGE = 1011;
    public static final int UPLOAD_MIME_NOT_ALLOWED = 1012;
    public static final int UPLOAD_FAILED = 1013;

    // 分类/标签
    public static final int SLUG_INVALID = 1020;
    public static final int SLUG_DUPLICATED = 1021;
    public static final int NAME_DUPLICATED = 1022;
    public static final int RESOURCE_IN_USE = 1023;
```

- [ ] **Step 2：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/common/ErrorCode.java
git commit -m "feat(backend): 扩展上传与分类/标签业务码"
```

---

### Task A5：`UploadService`（含 TDD）

**Files:**
- Create: `backend/src/main/java/com/blog/upload/service/UploadService.java`
- Create: `backend/src/main/java/com/blog/upload/vo/UploadResult.java`
- Test: `backend/src/test/java/com/blog/upload/service/UploadServiceTest.java`

`UploadService.save(MultipartFile)` 负责：
1. 空文件检查
2. 大小检查
3. 扩展名白名单
4. Tika 嗅探真实 MIME 与白名单比对（防止改后缀绕过）
5. 按 `yyyy/MM/` 落盘，UUID 重命名
6. 返回 `{url, size, mime}`

- [ ] **Step 1：写 `UploadResult.java`**

```java
package com.blog.upload.vo;

public record UploadResult(String url, long size, String mime) {}
```

- [ ] **Step 2：先写测试 `UploadServiceTest.java`**

```java
package com.blog.upload.service;

import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.upload.config.UploadProperties;
import com.blog.upload.vo.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UploadServiceTest {

    @TempDir Path tmp;

    private UploadService service;

    // 最小 PNG（8 字节 PNG signature + IHDR 等截断，足以让 Tika 识别 image/png）
    private static final byte[] PNG_BYTES = new byte[]{
            (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4,
            (byte)0x89
    };

    @BeforeEach
    void setUp() {
        UploadProperties props = new UploadProperties();
        props.setDir(tmp.toString());
        props.setUrlPrefix("/uploads");
        props.setMaxSizeBytes(5 * 1024 * 1024);
        props.setAllowedMime(Set.of("image/jpeg", "image/png", "image/webp", "image/gif"));
        props.setAllowedExt(Set.of("jpg", "jpeg", "png", "webp", "gif"));
        service = new UploadService(props);
    }

    @Test
    void empty_file_rejected() {
        var file = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(file));
        assertEquals(ErrorCode.UPLOAD_FILE_EMPTY, ex.getCode());
    }

    @Test
    void too_large_rejected() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        var file = new MockMultipartFile("file", "x.png", "image/png", big);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(file));
        assertEquals(ErrorCode.UPLOAD_FILE_TOO_LARGE, ex.getCode());
    }

    @Test
    void bad_extension_rejected() {
        var file = new MockMultipartFile("file", "evil.exe", "image/png", PNG_BYTES);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(file));
        assertEquals(ErrorCode.UPLOAD_MIME_NOT_ALLOWED, ex.getCode());
    }

    @Test
    void mime_mismatch_rejected() throws IOException {
        // 后缀 .png 但内容是纯文本，Tika 嗅出 text/plain
        var file = new MockMultipartFile("file", "fake.png", "image/png", "not a png".getBytes());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.save(file));
        assertEquals(ErrorCode.UPLOAD_MIME_NOT_ALLOWED, ex.getCode());
    }

    @Test
    void valid_png_saved_to_disk_and_url_returned() throws IOException {
        var file = new MockMultipartFile("file", "ok.png", "image/png", PNG_BYTES);
        UploadResult r = service.save(file);

        assertEquals("image/png", r.mime());
        assertEquals(PNG_BYTES.length, r.size());
        assertTrue(r.url().startsWith("/uploads/"));
        assertTrue(r.url().endsWith(".png"));

        // 文件落盘存在
        Path relative = Path.of(r.url().substring("/uploads/".length()));
        Path saved = tmp.resolve(relative);
        assertTrue(Files.exists(saved));
        assertEquals(PNG_BYTES.length, Files.size(saved));
    }
}
```

- [ ] **Step 3：跑测试看红**

Run: `cd backend && ./mvnw -q -Dtest=UploadServiceTest test`
Expected：编译失败，`UploadService` 不存在。

- [ ] **Step 4：写 `UploadService.java`**

```java
package com.blog.upload.service;

import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.upload.config.UploadProperties;
import com.blog.upload.vo.UploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final UploadProperties props;
    private final Tika tika = new Tika();

    public UploadResult save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_EMPTY, "文件为空");
        }
        if (file.getSize() > props.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE,
                    "文件超过 " + (props.getMaxSizeBytes() / 1024 / 1024) + "MB");
        }

        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extractExt(originalName);
        if (ext.isEmpty() || !props.getAllowedExt().contains(ext)) {
            throw new BusinessException(ErrorCode.UPLOAD_MIME_NOT_ALLOWED,
                    "不支持的扩展名: " + ext);
        }

        String detectedMime;
        try (InputStream is = file.getInputStream()) {
            detectedMime = tika.detect(is);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "读取文件失败");
        }
        if (!props.getAllowedMime().contains(detectedMime)) {
            throw new BusinessException(ErrorCode.UPLOAD_MIME_NOT_ALLOWED,
                    "文件类型不允许: " + detectedMime);
        }

        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path relative = Paths.get(datePath, filename);
        Path absolute = Paths.get(props.getDir()).toAbsolutePath().resolve(relative);

        try {
            Files.createDirectories(absolute.getParent());
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, absolute);
            }
        } catch (IOException e) {
            log.error("文件落盘失败 {}", absolute, e);
            throw new BusinessException(ErrorCode.UPLOAD_FAILED, "保存文件失败");
        }

        String url = props.getUrlPrefix() + "/" + relative.toString().replace('\\', '/');
        return new UploadResult(url, file.getSize(), detectedMime);
    }

    private String extractExt(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }
}
```

- [ ] **Step 5：跑测试看绿**

Run: `cd backend && ./mvnw -q -Dtest=UploadServiceTest test`
Expected：BUILD SUCCESS，5 用例全过。

- [ ] **Step 6：commit**

```bash
git add backend/src/main/java/com/blog/upload backend/src/test/java/com/blog/upload
git commit -m "feat(backend): UploadService 含大小/扩展名/MIME 三重校验"
```

---

### Task A6：`UploadController`

**Files:**
- Create: `backend/src/main/java/com/blog/upload/controller/UploadController.java`

- [ ] **Step 1：写 controller**

```java
package com.blog.upload.controller;

import com.blog.common.Result;
import com.blog.upload.service.UploadService;
import com.blog.upload.vo.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @PostMapping
    public Result<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(uploadService.save(file));
    }
}
```

- [ ] **Step 2：跑全量测试确认没破坏 M1**

Run: `cd backend && ./mvnw -q test`
Expected：14 个用例（M1 的 9 + UploadServiceTest 的 5）全过。

- [ ] **Step 3：手动验证（启动应用 + curl）**

```bash
cd backend && ./mvnw -q spring-boot:run > /tmp/boot.log 2>&1 &
sleep 25
grep -E "Started BlogApplication|ERROR" /tmp/boot.log | head -5
```

获取 token：
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d);process.stdin.on('end',()=>console.log(JSON.parse(s).data.token))")
echo "TOKEN=${TOKEN:0:40}..."
```

匿名上传（应 1401）：
```bash
curl -s -X POST http://localhost:8080/api/admin/upload \
  -F "file=@docs/superpowers/specs/2026-05-21-blog-system-design.md"
```
Expected：`{"code":1401,"message":"未登录或登录已过期","data":null}`

上传 .md 文件（应被拒）：
```bash
curl -s -X POST http://localhost:8080/api/admin/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@docs/superpowers/specs/2026-05-21-blog-system-design.md"
```
Expected：`code: 1012`（扩展名不允许）。

准备一张真 png 测试上传：
```bash
# 用 ImageMagick 或者从 frontend 找一张；这里用 base64 生成一个 1x1 透明 png
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+P+/HgAFhAJ/wlseKgAAAABJRU5ErkJggg==" \
  | base64 -d > /tmp/tiny.png
curl -s -X POST http://localhost:8080/api/admin/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/tiny.png"
```
Expected：`{"code":0,"message":"ok","data":{"url":"/uploads/2026/05/...png","size":68,"mime":"image/png"}}`

访问静态资源：
```bash
URL=$(... 上一步返回的 url)
curl -sI http://localhost:8080$URL | head -3
```
Expected：`HTTP/1.1 200`、`Content-Type: image/png`。

停服：`taskkill //F //IM java.exe`

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/upload/controller
git commit -m "feat(backend): 添加 POST /api/admin/upload 接口"
```

---

## Phase B：分类后端

### Task B1：Flyway V2 迁移 — `category` 表

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__init_category_tag.sql`

把 spec 里的 category 与 tag 两张表一起在 V2 里建掉（M2.2 再加 article/article_tag 用 V3）。

- [ ] **Step 1：写 V2 SQL**

```sql
-- 分类
CREATE TABLE IF NOT EXISTS category (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(50) UNIQUE NOT NULL,
  slug        VARCHAR(50) UNIQUE NOT NULL,
  description VARCHAR(255),
  sort        INT DEFAULT 0,
  deleted     BOOLEAN DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- 标签
CREATE TABLE IF NOT EXISTS tag (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(30) UNIQUE NOT NULL,
  slug       VARCHAR(30) UNIQUE NOT NULL,
  deleted    BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT now()
);
```

> 注意：spec 里 unique 是不带 deleted 条件的全局唯一。软删除后想复用同名会冲突——M2.1 阶段先按 spec 来，遇到时再讨论是否改部分唯一索引 `WHERE deleted = false`。

- [ ] **Step 2：启动 Spring Boot 让 Flyway 跑 V2**

```bash
cd backend && ./mvnw -q spring-boot:run > /tmp/boot.log 2>&1 &
sleep 25
grep -E "Migrating to version|Successfully applied|ERROR" /tmp/boot.log | head -10
```
Expected：看到 `Migrating schema "public" to version "2"`、`Successfully applied 1 migration`（已有 V1，本次新增 1）。`taskkill //F //IM java.exe` 停服。

- [ ] **Step 3：验证表**

```bash
docker exec -i pgsql-win psql -U blog -d blog -c '\dt'
docker exec -i pgsql-win psql -U blog -d blog -c '\d category'
docker exec -i pgsql-win psql -U blog -d blog -c '\d tag'
```
Expected：能看到 user、category、tag 三张表 + flyway_schema_history。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/resources/db/migration/V2__init_category_tag.sql
git commit -m "feat(backend): Flyway V2 创建 category 与 tag 表"
```

---

### Task B2：`CategoryEntity` + `CategoryMapper`

**Files:**
- Create: `backend/src/main/java/com/blog/module/category/entity/CategoryEntity.java`
- Create: `backend/src/main/java/com/blog/module/category/mapper/CategoryMapper.java`

- [ ] **Step 1：`CategoryEntity.java`**

```java
package com.blog.module.category.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("category")
public class CategoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String slug;
    private String description;
    private Integer sort;

    @TableLogic
    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 2：`CategoryMapper.java`**

```java
package com.blog.module.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.category.entity.CategoryEntity;

public interface CategoryMapper extends BaseMapper<CategoryEntity> {
}
```

- [ ] **Step 3：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/module/category
git commit -m "feat(backend): 添加 CategoryEntity 与 CategoryMapper"
```

---

### Task B3：分类 DTO/VO

**Files:**
- Create: `backend/src/main/java/com/blog/module/category/dto/CategoryUpsertRequest.java`
- Create: `backend/src/main/java/com/blog/module/category/vo/CategoryVO.java`

- [ ] **Step 1：`CategoryUpsertRequest.java`**

```java
package com.blog.module.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CategoryUpsertRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "slug 只能包含小写字母、数字和短横线")
        String slug,
        @Size(max = 255) String description,
        Integer sort
) {}
```

- [ ] **Step 2：`CategoryVO.java`**

```java
package com.blog.module.category.vo;

public record CategoryVO(
        Long id,
        String name,
        String slug,
        String description,
        Integer sort,
        long articleCount
) {}
```

- [ ] **Step 3：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/module/category/dto backend/src/main/java/com/blog/module/category/vo
git commit -m "feat(backend): 添加 Category DTO/VO"
```

---

### Task B4：`CategoryService` + 单元测试

**Files:**
- Create: `backend/src/main/java/com/blog/module/category/service/CategoryService.java`
- Test: `backend/src/test/java/com/blog/module/category/service/CategoryServiceTest.java`

`CategoryService` 提供：
- `list()` — 所有分类（按 sort 升序、id 升序），文章计数 M2.1 阶段恒 0
- `create(req)` — name/slug 唯一校验
- `update(id, req)` — name/slug 唯一校验（排除自身）
- `delete(id)` — 软删除（M2.2 再加"被文章引用时禁止删除"）

- [ ] **Step 1：先写测试 `CategoryServiceTest.java`**

集成测试，连真 PG。用 `@SpringBootTest` + `@Transactional` + `@Rollback` 让每个用例隔离。

```java
package com.blog.module.category.service;

import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.module.category.dto.CategoryUpsertRequest;
import com.blog.module.category.vo.CategoryVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@Rollback
class CategoryServiceTest {

    @Autowired CategoryService service;

    @Test
    void create_then_list_returns_it() {
        CategoryVO created = service.create(new CategoryUpsertRequest("技术", "tech", "技术分享", 1));
        assertNotNull(created.id());
        assertEquals("tech", created.slug());

        List<CategoryVO> all = service.list();
        assertTrue(all.stream().anyMatch(c -> c.slug().equals("tech")));
    }

    @Test
    void duplicate_slug_rejected() {
        service.create(new CategoryUpsertRequest("Tech1", "dup-tech", "", 0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new CategoryUpsertRequest("Tech2", "dup-tech", "", 0)));
        assertEquals(ErrorCode.SLUG_DUPLICATED, ex.getCode());
    }

    @Test
    void duplicate_name_rejected() {
        service.create(new CategoryUpsertRequest("DupName", "dup-name-1", "", 0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new CategoryUpsertRequest("DupName", "dup-name-2", "", 0)));
        assertEquals(ErrorCode.NAME_DUPLICATED, ex.getCode());
    }

    @Test
    void update_changes_fields_and_keeps_slug_unique() {
        CategoryVO a = service.create(new CategoryUpsertRequest("A", "slug-a", "", 0));
        CategoryVO b = service.create(new CategoryUpsertRequest("B", "slug-b", "", 0));

        CategoryVO updated = service.update(a.id(), new CategoryUpsertRequest("A-new", "slug-a-new", "desc", 5));
        assertEquals("A-new", updated.name());
        assertEquals("slug-a-new", updated.slug());
        assertEquals(5, updated.sort());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(a.id(), new CategoryUpsertRequest("A2", "slug-b", "", 0)));
        assertEquals(ErrorCode.SLUG_DUPLICATED, ex.getCode());
    }

    @Test
    void delete_soft_deletes_and_excludes_from_list() {
        CategoryVO c = service.create(new CategoryUpsertRequest("DelMe", "delme", "", 0));
        service.delete(c.id());

        assertFalse(service.list().stream().anyMatch(x -> x.id().equals(c.id())));
    }

    @Test
    void delete_nonexistent_throws_not_found() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(9999999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getCode());
    }
}
```

- [ ] **Step 2：跑测试看红**

Run: `cd backend && ./mvnw -q -Dtest=CategoryServiceTest test`
Expected：编译失败，`CategoryService` 不存在。

- [ ] **Step 3：写 `CategoryService.java`**

```java
package com.blog.module.category.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.module.category.dto.CategoryUpsertRequest;
import com.blog.module.category.entity.CategoryEntity;
import com.blog.module.category.mapper.CategoryMapper;
import com.blog.module.category.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper mapper;

    public List<CategoryVO> list() {
        List<CategoryEntity> all = mapper.selectList(
                Wrappers.<CategoryEntity>lambdaQuery()
                        .orderByAsc(CategoryEntity::getSort)
                        .orderByAsc(CategoryEntity::getId));
        return all.stream().map(this::toVO).toList();
    }

    @Transactional
    public CategoryVO create(CategoryUpsertRequest req) {
        ensureNameUnique(req.name(), null);
        ensureSlugUnique(req.slug(), null);

        CategoryEntity e = new CategoryEntity();
        e.setName(req.name());
        e.setSlug(req.slug());
        e.setDescription(req.description());
        e.setSort(req.sort() == null ? 0 : req.sort());
        e.setDeleted(false);
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        mapper.insert(e);
        return toVO(e);
    }

    @Transactional
    public CategoryVO update(Long id, CategoryUpsertRequest req) {
        CategoryEntity exists = mapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        ensureNameUnique(req.name(), id);
        ensureSlugUnique(req.slug(), id);

        exists.setName(req.name());
        exists.setSlug(req.slug());
        exists.setDescription(req.description());
        exists.setSort(req.sort() == null ? 0 : req.sort());
        exists.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(exists);
        return toVO(exists);
    }

    @Transactional
    public void delete(Long id) {
        CategoryEntity exists = mapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        mapper.deleteById(id);
    }

    private void ensureSlugUnique(String slug, Long excludeId) {
        Long count = mapper.selectCount(
                Wrappers.<CategoryEntity>lambdaQuery()
                        .eq(CategoryEntity::getSlug, slug)
                        .ne(excludeId != null, CategoryEntity::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.SLUG_DUPLICATED, "slug 已存在");
        }
    }

    private void ensureNameUnique(String name, Long excludeId) {
        Long count = mapper.selectCount(
                Wrappers.<CategoryEntity>lambdaQuery()
                        .eq(CategoryEntity::getName, name)
                        .ne(excludeId != null, CategoryEntity::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.NAME_DUPLICATED, "分类名已存在");
        }
    }

    private CategoryVO toVO(CategoryEntity e) {
        return new CategoryVO(e.getId(), e.getName(), e.getSlug(),
                e.getDescription(), e.getSort(), 0L);
    }
}
```

- [ ] **Step 4：跑测试看绿**

Run: `cd backend && ./mvnw -q -Dtest=CategoryServiceTest test`
Expected：BUILD SUCCESS，6 用例全过。

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/category/service backend/src/test/java/com/blog/module/category
git commit -m "feat(backend): CategoryService 含 CRUD 与唯一性校验"
```

---

### Task B5：`CategoryController`（公开 + 后台）

**Files:**
- Create: `backend/src/main/java/com/blog/module/category/controller/CategoryController.java`
- Create: `backend/src/main/java/com/blog/module/category/controller/AdminCategoryController.java`

按 spec：公开 `GET /api/categories` + 后台 CRUD `/api/admin/categories`。

- [ ] **Step 1：`CategoryController.java`**

```java
package com.blog.module.category.controller;

import com.blog.common.Result;
import com.blog.module.category.service.CategoryService;
import com.blog.module.category.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService service;

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(service.list());
    }
}
```

- [ ] **Step 2：`AdminCategoryController.java`**

```java
package com.blog.module.category.controller;

import com.blog.common.Result;
import com.blog.module.category.dto.CategoryUpsertRequest;
import com.blog.module.category.service.CategoryService;
import com.blog.module.category.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService service;

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    public Result<CategoryVO> create(@RequestBody @Valid CategoryUpsertRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<CategoryVO> update(@PathVariable Long id, @RequestBody @Valid CategoryUpsertRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 3：跑全量测试**

Run: `cd backend && ./mvnw -q test`
Expected：M1 9 + Upload 5 + Category 6 = 20 个用例全过。

- [ ] **Step 4：手动 curl 验证**

启动应用，登录拿 token，跑：
```bash
# 公开列表
curl -s http://localhost:8080/api/categories | head -c 200

# 后台未鉴权 → 1401
curl -s -X POST http://localhost:8080/api/admin/categories \
  -H "Content-Type: application/json" \
  -d '{"name":"X","slug":"x","sort":0}'

# 后台创建
curl -s -X POST http://localhost:8080/api/admin/categories \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"技术","slug":"tech","description":"技术分享","sort":1}'

# bad slug 触发 1400
curl -s -X POST http://localhost:8080/api/admin/categories \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Bad","slug":"Bad SLUG!","sort":0}'

# 列表
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/categories
```
Expected：分别得到 `[]`/`1401`/`{code:0,data:{...}}`/`{code:1400,...slug...}`/含刚创建的分类。

停服：`taskkill //F //IM java.exe`

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/category/controller
git commit -m "feat(backend): CategoryController 公开列表与后台 CRUD"
```

---

## Phase C：标签后端

### Task C1：`TagEntity` + `TagMapper`

**Files:**
- Create: `backend/src/main/java/com/blog/module/tag/entity/TagEntity.java`
- Create: `backend/src/main/java/com/blog/module/tag/mapper/TagMapper.java`

- [ ] **Step 1：`TagEntity.java`**（标签**没有** updated_at；按 spec V2 SQL）

```java
package com.blog.module.tag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("tag")
public class TagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String slug;

    @TableLogic
    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 2：`TagMapper.java`**

```java
package com.blog.module.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.tag.entity.TagEntity;

public interface TagMapper extends BaseMapper<TagEntity> {
}
```

- [ ] **Step 3：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/module/tag
git commit -m "feat(backend): 添加 TagEntity 与 TagMapper"
```

---

### Task C2：标签 DTO/VO + Service + 测试

**Files:**
- Create: `backend/src/main/java/com/blog/module/tag/dto/TagUpsertRequest.java`
- Create: `backend/src/main/java/com/blog/module/tag/vo/TagVO.java`
- Create: `backend/src/main/java/com/blog/module/tag/service/TagService.java`
- Test: `backend/src/test/java/com/blog/module/tag/service/TagServiceTest.java`

- [ ] **Step 1：`TagUpsertRequest.java`**

```java
package com.blog.module.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TagUpsertRequest(
        @NotBlank @Size(max = 30) String name,
        @NotBlank @Size(max = 30)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "slug 只能包含小写字母、数字和短横线")
        String slug
) {}
```

- [ ] **Step 2：`TagVO.java`**

```java
package com.blog.module.tag.vo;

public record TagVO(
        Long id,
        String name,
        String slug,
        long useCount
) {}
```

- [ ] **Step 3：先写测试 `TagServiceTest.java`**

```java
package com.blog.module.tag.service;

import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.module.tag.dto.TagUpsertRequest;
import com.blog.module.tag.vo.TagVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@Rollback
class TagServiceTest {

    @Autowired TagService service;

    @Test
    void create_and_list() {
        TagVO java = service.create(new TagUpsertRequest("Java", "java"));
        assertNotNull(java.id());
        assertEquals(0L, java.useCount());
        assertTrue(service.list().stream().anyMatch(t -> t.slug().equals("java")));
    }

    @Test
    void duplicate_slug_rejected() {
        service.create(new TagUpsertRequest("DupTagA", "dup-tag"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new TagUpsertRequest("DupTagB", "dup-tag")));
        assertEquals(ErrorCode.SLUG_DUPLICATED, ex.getCode());
    }

    @Test
    void duplicate_name_rejected() {
        service.create(new TagUpsertRequest("DupName", "tag-a"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new TagUpsertRequest("DupName", "tag-b")));
        assertEquals(ErrorCode.NAME_DUPLICATED, ex.getCode());
    }

    @Test
    void update_keeps_unique() {
        TagVO a = service.create(new TagUpsertRequest("UA", "u-a"));
        TagVO b = service.create(new TagUpsertRequest("UB", "u-b"));
        TagVO updated = service.update(a.id(), new TagUpsertRequest("UA-new", "u-a-new"));
        assertEquals("u-a-new", updated.slug());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(a.id(), new TagUpsertRequest("UA2", "u-b")));
        assertEquals(ErrorCode.SLUG_DUPLICATED, ex.getCode());
    }

    @Test
    void delete_soft_deletes() {
        TagVO t = service.create(new TagUpsertRequest("Del", "del-t"));
        service.delete(t.id());
        assertFalse(service.list().stream().anyMatch(x -> x.id().equals(t.id())));
    }

    @Test
    void delete_nonexistent_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(9999998L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getCode());
    }
}
```

- [ ] **Step 4：跑测试看红**

Run: `cd backend && ./mvnw -q -Dtest=TagServiceTest test`
Expected：编译失败，`TagService` 不存在。

- [ ] **Step 5：写 `TagService.java`**

```java
package com.blog.module.tag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.module.tag.dto.TagUpsertRequest;
import com.blog.module.tag.entity.TagEntity;
import com.blog.module.tag.mapper.TagMapper;
import com.blog.module.tag.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper mapper;

    public List<TagVO> list() {
        List<TagEntity> all = mapper.selectList(
                Wrappers.<TagEntity>lambdaQuery().orderByAsc(TagEntity::getId));
        return all.stream().map(this::toVO).toList();
    }

    @Transactional
    public TagVO create(TagUpsertRequest req) {
        ensureNameUnique(req.name(), null);
        ensureSlugUnique(req.slug(), null);

        TagEntity e = new TagEntity();
        e.setName(req.name());
        e.setSlug(req.slug());
        e.setDeleted(false);
        e.setCreatedAt(OffsetDateTime.now());
        mapper.insert(e);
        return toVO(e);
    }

    @Transactional
    public TagVO update(Long id, TagUpsertRequest req) {
        TagEntity exists = mapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        ensureNameUnique(req.name(), id);
        ensureSlugUnique(req.slug(), id);
        exists.setName(req.name());
        exists.setSlug(req.slug());
        mapper.updateById(exists);
        return toVO(exists);
    }

    @Transactional
    public void delete(Long id) {
        TagEntity exists = mapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        mapper.deleteById(id);
    }

    private void ensureSlugUnique(String slug, Long excludeId) {
        Long count = mapper.selectCount(
                Wrappers.<TagEntity>lambdaQuery()
                        .eq(TagEntity::getSlug, slug)
                        .ne(excludeId != null, TagEntity::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.SLUG_DUPLICATED, "slug 已存在");
        }
    }

    private void ensureNameUnique(String name, Long excludeId) {
        Long count = mapper.selectCount(
                Wrappers.<TagEntity>lambdaQuery()
                        .eq(TagEntity::getName, name)
                        .ne(excludeId != null, TagEntity::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.NAME_DUPLICATED, "标签名已存在");
        }
    }

    private TagVO toVO(TagEntity e) {
        return new TagVO(e.getId(), e.getName(), e.getSlug(), 0L);
    }
}
```

- [ ] **Step 6：跑测试看绿**

Run: `cd backend && ./mvnw -q -Dtest=TagServiceTest test`
Expected：BUILD SUCCESS，6 用例全过。

- [ ] **Step 7：commit**

```bash
git add backend/src/main/java/com/blog/module/tag/dto backend/src/main/java/com/blog/module/tag/vo backend/src/main/java/com/blog/module/tag/service backend/src/test/java/com/blog/module/tag
git commit -m "feat(backend): TagService 含 CRUD 与唯一性校验"
```

---

### Task C3：`TagController`（公开 + 后台）

**Files:**
- Create: `backend/src/main/java/com/blog/module/tag/controller/TagController.java`
- Create: `backend/src/main/java/com/blog/module/tag/controller/AdminTagController.java`

- [ ] **Step 1：`TagController.java`**

```java
package com.blog.module.tag.controller;

import com.blog.common.Result;
import com.blog.module.tag.service.TagService;
import com.blog.module.tag.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService service;

    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(service.list());
    }
}
```

- [ ] **Step 2：`AdminTagController.java`**

```java
package com.blog.module.tag.controller;

import com.blog.common.Result;
import com.blog.module.tag.dto.TagUpsertRequest;
import com.blog.module.tag.service.TagService;
import com.blog.module.tag.vo.TagVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
public class AdminTagController {

    private final TagService service;

    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    public Result<TagVO> create(@RequestBody @Valid TagUpsertRequest req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<TagVO> update(@PathVariable Long id, @RequestBody @Valid TagUpsertRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 3：跑全量测试**

Run: `cd backend && ./mvnw -q test`
Expected：26 用例（M1 9 + Upload 5 + Category 6 + Tag 6）全过。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/java/com/blog/module/tag/controller
git commit -m "feat(backend): TagController 公开标签云与后台 CRUD"
```

---

## Phase D：前端共享基础

### Task D1：分类/标签的 API 模块 + 类型

**Files:**
- Modify: `frontend/src/types/index.ts`
- Create: `frontend/src/api/category.ts`
- Create: `frontend/src/api/tag.ts`
- Create: `frontend/src/api/upload.ts`

- [ ] **Step 1：在 `frontend/src/types/index.ts` 末尾追加类型**

```ts
export interface Category {
  id: number
  name: string
  slug: string
  description?: string
  sort: number
  articleCount: number
}

export interface CategoryUpsert {
  name: string
  slug: string
  description?: string
  sort?: number
}

export interface Tag {
  id: number
  name: string
  slug: string
  useCount: number
}

export interface TagUpsert {
  name: string
  slug: string
}

export interface UploadResult {
  url: string
  size: number
  mime: string
}
```

- [ ] **Step 2：`frontend/src/api/category.ts`**

```ts
import request from './request'
import type { Category, CategoryUpsert } from '@/types'

export function listCategories() {
  return request.get<unknown, Category[]>('/categories')
}

export function listAdminCategories() {
  return request.get<unknown, Category[]>('/admin/categories')
}

export function createCategory(body: CategoryUpsert) {
  return request.post<unknown, Category>('/admin/categories', body)
}

export function updateCategory(id: number, body: CategoryUpsert) {
  return request.put<unknown, Category>(`/admin/categories/${id}`, body)
}

export function deleteCategory(id: number) {
  return request.delete<unknown, void>(`/admin/categories/${id}`)
}
```

- [ ] **Step 3：`frontend/src/api/tag.ts`**

```ts
import request from './request'
import type { Tag, TagUpsert } from '@/types'

export function listTags() {
  return request.get<unknown, Tag[]>('/tags')
}

export function listAdminTags() {
  return request.get<unknown, Tag[]>('/admin/tags')
}

export function createTag(body: TagUpsert) {
  return request.post<unknown, Tag>('/admin/tags', body)
}

export function updateTag(id: number, body: TagUpsert) {
  return request.put<unknown, Tag>(`/admin/tags/${id}`, body)
}

export function deleteTag(id: number) {
  return request.delete<unknown, void>(`/admin/tags/${id}`)
}
```

- [ ] **Step 4：`frontend/src/api/upload.ts`**

```ts
import request from './request'
import type { UploadResult } from '@/types'

export function uploadFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<unknown, UploadResult>('/admin/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
```

- [ ] **Step 5：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/types frontend/src/api
git commit -m "feat(frontend): 添加 category/tag/upload API 与类型"
```

---

### Task D2：`AdminLayout` 加 sidebar 导航

**Files:**
- Modify: `frontend/src/layouts/AdminLayout.vue`

把 AdminLayout 从 header-only 改为 header + sidebar 结构，加分类/标签/dashboard 三个菜单项。

- [ ] **Step 1：完整覆盖 `frontend/src/layouts/AdminLayout.vue`**

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
    <el-container>
      <el-aside width="200px" class="bg-white border-r">
        <el-menu :default-active="route.path" router class="border-r-0">
          <el-menu-item index="/admin/dashboard">
            <span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/admin/categories">
            <span>分类管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/tags">
            <span>标签管理</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="bg-gray-50">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { user } = storeToRefs(userStore)

async function handleLogout() {
  await userStore.logout()
  router.push('/admin/login')
}
</script>
```

- [ ] **Step 2：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/layouts/AdminLayout.vue
git commit -m "feat(frontend): AdminLayout 加 sidebar 与分类/标签菜单"
```

---

## Phase E：前端管理页面

### Task E1：分类管理页 `CategoryView.vue`

**Files:**
- Create: `frontend/src/views/admin/CategoryView.vue`
- Modify: `frontend/src/router/index.ts`

提供：
- 表格列出所有分类（按 sort）
- "新建分类"按钮 → 打开 Dialog（form: name/slug/description/sort）
- 每行有"编辑"/"删除"按钮，编辑复用同一 Dialog（v-model 数据绑定 + 标题切换）
- 删除二次确认（`ElMessageBox.confirm`）

- [ ] **Step 1：`CategoryView.vue`**

```vue
<template>
  <div class="bg-white rounded p-6">
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-xl font-semibold">分类管理</h2>
      <el-button type="primary" @click="openCreate">新建分类</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="slug" label="slug" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column prop="sort" label="排序" width="80" />
      <el-table-column prop="articleCount" label="文章数" width="100" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="slug" prop="slug">
          <el-input v-model="form.slug" maxlength="50" placeholder="小写字母/数字/短横线" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" maxlength="255" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listAdminCategories,
  createCategory,
  updateCategory,
  deleteCategory,
} from '@/api/category'
import type { Category, CategoryUpsert } from '@/types'

const rows = ref<Category[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新建分类')
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<CategoryUpsert>({
  name: '',
  slug: '',
  description: '',
  sort: 0,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  slug: [
    { required: true, message: '请输入 slug', trigger: 'blur' },
    { pattern: /^[a-z0-9-]+$/, message: '只能包含小写字母、数字和短横线', trigger: 'blur' },
  ],
}

async function load() {
  loading.value = true
  try {
    rows.value = await listAdminCategories()
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.slug = ''
  form.description = ''
  form.sort = 0
  editingId.value = null
  formRef.value?.clearValidate()
}

function openCreate() {
  resetForm()
  dialogTitle.value = '新建分类'
  dialogVisible.value = true
}

function openEdit(row: Category) {
  resetForm()
  dialogTitle.value = '编辑分类'
  editingId.value = row.id
  form.name = row.name
  form.slug = row.slug
  form.description = row.description ?? ''
  form.sort = row.sort
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (editingId.value == null) {
      await createCategory({ ...form })
      ElMessage.success('已创建')
    } else {
      await updateCategory(editingId.value, { ...form })
      ElMessage.success('已更新')
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: Category) {
  const ok = await ElMessageBox.confirm(`确定删除分类「${row.name}」吗？`, '确认', {
    type: 'warning',
  }).catch(() => false)
  if (!ok) return
  await deleteCategory(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>
```

- [ ] **Step 2：在 `frontend/src/router/index.ts` 的 children 里加路由**

定位 `/admin` 子路由数组：
```ts
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('@/views/admin/DashboardView.vue') },
    ],
```

改为：
```ts
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('@/views/admin/DashboardView.vue') },
      { path: 'categories', component: () => import('@/views/admin/CategoryView.vue') },
      { path: 'tags', component: () => import('@/views/admin/TagView.vue') },
    ],
```

> 注意：现在 import 的 `TagView.vue` 还没建，下个 Task 才建。本步先建空占位避免类型检查炸：
>
> 创建 `frontend/src/views/admin/TagView.vue`：
> ```vue
> <template><div>tag placeholder</div></template>
> <script setup lang="ts"></script>
> ```

- [ ] **Step 3：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/views/admin/CategoryView.vue frontend/src/views/admin/TagView.vue frontend/src/router/index.ts
git commit -m "feat(frontend): 分类管理页与路由（含 tag 占位）"
```

---

### Task E2：标签管理页 `TagView.vue`

**Files:**
- Modify: `frontend/src/views/admin/TagView.vue`（覆盖上一步占位）

- [ ] **Step 1：完整覆盖 `TagView.vue`**

```vue
<template>
  <div class="bg-white rounded p-6">
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-xl font-semibold">标签管理</h2>
      <el-button type="primary" @click="openCreate">新建标签</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="slug" label="slug" />
      <el-table-column prop="useCount" label="使用次数" width="120" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="400px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="60px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="30" />
        </el-form-item>
        <el-form-item label="slug" prop="slug">
          <el-input v-model="form.slug" maxlength="30" placeholder="小写字母/数字/短横线" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listAdminTags, createTag, updateTag, deleteTag } from '@/api/tag'
import type { Tag, TagUpsert } from '@/types'

const rows = ref<Tag[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新建标签')
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<TagUpsert>({ name: '', slug: '' })

const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  slug: [
    { required: true, message: '请输入 slug', trigger: 'blur' },
    { pattern: /^[a-z0-9-]+$/, message: '只能包含小写字母、数字和短横线', trigger: 'blur' },
  ],
}

async function load() {
  loading.value = true
  try {
    rows.value = await listAdminTags()
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.slug = ''
  editingId.value = null
  formRef.value?.clearValidate()
}

function openCreate() {
  resetForm()
  dialogTitle.value = '新建标签'
  dialogVisible.value = true
}

function openEdit(row: Tag) {
  resetForm()
  dialogTitle.value = '编辑标签'
  editingId.value = row.id
  form.name = row.name
  form.slug = row.slug
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (editingId.value == null) {
      await createTag({ ...form })
      ElMessage.success('已创建')
    } else {
      await updateTag(editingId.value, { ...form })
      ElMessage.success('已更新')
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: Tag) {
  const ok = await ElMessageBox.confirm(`确定删除标签「${row.name}」吗？`, '确认', {
    type: 'warning',
  }).catch(() => false)
  if (!ok) return
  await deleteTag(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>
```

- [ ] **Step 2：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/views/admin/TagView.vue
git commit -m "feat(frontend): 标签管理页"
```

---

## Phase F：端到端验收与收尾

### Task F1：浏览器人工验收

**Files:** 无新增，纯人工/浏览器验证。

前置：保持 pgsql-win 容器在跑。

- [ ] **Step 1：启动后端**

```bash
cd backend && ./mvnw -q spring-boot:run > /tmp/boot.log 2>&1 &
sleep 25
grep "Started BlogApplication" /tmp/boot.log
```
Expected：看到 `Started BlogApplication`。

- [ ] **Step 2：启动前端**

```bash
cd frontend && pnpm dev > /tmp/frontend.log 2>&1 &
sleep 10
grep "Local:" /tmp/frontend.log
```
Expected：`Local: http://localhost:5173/`。

- [ ] **Step 3：浏览器验收**

打开 http://localhost:5173/admin/login，用 `admin / admin123` 登录。验证以下场景：

**分类管理（`/admin/categories`）**
- 列表初始为空
- 点"新建分类"→ 填 `技术 / tech / 技术分享 / 1` → 保存 → 列表新增一行，文章数=0
- 编辑该分类，把 sort 改为 5 → 保存 → 列表显示 sort=5
- 新建分类 slug 用 `Bad SLUG!` → 表单校验提示"只能包含小写字母、数字和短横线"
- 新建分类 slug 用 `tech`（与现有重复）→ toast "slug 已存在"
- 删除某分类 → 二次确认 → 列表移除该行

**标签管理（`/admin/tags`）**
- 列表初始为空
- 新建 `Java / java` → 列表新增，使用次数=0
- 重复 slug `java` 创建 → toast "slug 已存在"
- 编辑、删除流程与分类一致

**未登录态保护**
- 退出登录，直接访问 http://localhost:5173/admin/categories → 自动跳 `/admin/login?redirect=%2Fadmin%2Fcategories`，登录后回到分类管理页

**文件上传（用浏览器 DevTools Network panel 触发，或用 curl）**
- 在 DevTools Console 跑：
  ```js
  // 选一张本地 png（< 5MB）拖到一个 input[type=file]，然后：
  fetch('/api/admin/upload', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + localStorage.getItem('blog_token') },
    body: (() => { const f = new FormData(); f.append('file', document.querySelector('input[type=file]').files[0]); return f })()
  }).then(r => r.json()).then(console.log)
  ```
- 期望返回 `{code:0, data:{url:"/uploads/2026/05/xxx.png", size:..., mime:"image/png"}}`
- 直接访问 `http://localhost:5173/uploads/2026/05/xxx.png`（vite proxy → 8080）能看到图

> 注意：M2.1 暂没有用上传的 UI 入口（编辑器在 M2.2 接入）。上传通过 API 直接验证即可。

- [ ] **Step 4：停服**

```bash
taskkill //F //IM java.exe
taskkill //F //IM node.exe
```

> 如果浏览器场景任一失败：回到对应 Task 修。

---

### Task F2：更新 README

**Files:**
- Modify: `README.md`

- [ ] **Step 1：在 README 的"当前进度"段下追加 M2.1 行**

把当前的：
```markdown
## 当前进度

M1 骨架（仓库初始化 + JWT 登录闭环）已完成。后续里程碑见 `docs/superpowers/specs/`。
```

改为：
```markdown
## 当前进度

- M1 骨架（仓库 + JWT 登录闭环）— 已完成
- M2.1（文件上传 + 分类 + 标签管理）— 已完成
- M2.2（文章 CRUD + Markdown 渲染）— 进行中

详细里程碑见 `docs/superpowers/specs/`，每个里程碑的实现计划见 `docs/superpowers/plans/`。
```

- [ ] **Step 2：commit + push**

```bash
git add README.md
git commit -m "docs: 更新 README 标注 M2.1 完成"
git push origin main
```
Expected：成功推送。

---

## M2.1 验收清单

- [x] `./mvnw test` 全绿，26 个用例（M1 9 + Upload 5 + Category 6 + Tag 6）
- [x] Flyway V2 自动创建 `category` 与 `tag` 表
- [x] `POST /api/admin/upload` 拒空文件 / 超大 / 错扩展名 / MIME 不符；接受合法 png/jpeg/webp/gif；落盘 `backend/uploads/yyyy/MM/uuid.ext`
- [x] `/uploads/**` 静态资源可访问
- [x] `GET /api/categories` 公开返回所有分类（M2.1 阶段 articleCount 恒 0）
- [x] `/api/admin/categories` 后台 CRUD 含 name/slug 唯一性
- [x] `GET /api/tags` 公开返回所有标签（useCount 恒 0）
- [x] `/api/admin/tags` 后台 CRUD 含 name/slug 唯一性
- [x] 前端 admin 有 sidebar 导航；分类/标签管理页可用
- [x] 未登录直接访问 `/admin/categories` 自动跳登录页
- [x] 全部 commit 已推到 origin/main
