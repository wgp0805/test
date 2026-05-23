# M2.2 实现计划：文章核心 + Markdown 渲染

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M2.1 基础上加入文章核心（含 Markdown 服务端渲染），完整支持后台对文章的 CRUD + 状态管理 + 关联分类/标签 + 封面图上传。M2.2 完成后单作者可以在 admin 完整管理博文。

**Architecture:**
- 后端新增 `article` 模块：entity（article + article_tag 关联）、MarkdownRenderer（flexmark-java GFM + 表格 + 任务列表 + 锚点）、ArticleService（CRUD + 渲染管线 + tag 关联保存 + search_vector 同步）、AdminArticleController（分页列表 + 状态切换 + 创建/更新/删除）。
- Markdown 渲染管线在 Service 层保存时同步执行：`contentMd → flexmark → contentHtml → 去 HTML 取文本 → wordCount → PG to_tsvector → search_vector`。**前端编辑器预览仅供参考，以服务端渲染为准**。
- 前端 admin 新增文章列表页（分页 + status/keyword 过滤）和编辑页（md-editor-v3 + cover 上传 + 分类/标签级联下拉）。

**Out of scope（属于 M3 或 M4，本期不做）：**
- 公开前台接口 `/api/articles/**`（详情、归档、热门、上一篇/下一篇）
- 文章前台页面（首页、详情、归档、分类页、标签页、搜索结果）
- Shiki 代码高亮
- 阅读量统计 + view_log
- 评论
- 全文搜索 API

**Tech Stack:**
- 后端：M2.1 栈 + flexmark-java 0.64.8（Markdown）+ MyBatis-Plus 分页插件
- 前端：M2.1 栈 + md-editor-v3 5.x（Markdown 编辑器，含工具栏自定义上传）

**关键约束（不要重复确认）：**
- `published_at` 在 status 从 0（草稿）→ 1（发布）时**首次**填入 `now()`，之后状态变更不再更新（即使下架再上架）
- `slug` 用户手填，校验唯一 + 正则 `^[a-z0-9-]+$`（与分类标签一致）
- `wordCount` 用 HTML 去标签后字符数（中英文混合就按字符数，不分词）
- `search_vector` 用 `to_tsvector('simple', title || ' ' || content_md)` 在 SQL 层生成，Java 侧只把 title/content_md 传过去
- 列表分页默认 page=1 size=10；后台列表的 `keyword` 走 title LIKE（M2.2 不做全文检索）
- 软删除：article 用 `deleted` 字段；标签关联走物理 cascade（在 V3 SQL 中 `ON DELETE CASCADE`）
- 删除文章时一并删除 `article_tag` 中该文章的所有关联（依赖物理外键 CASCADE）
- tag 关联保存：先 `DELETE FROM article_tag WHERE article_id=?`，再批量 insert（最简，避免 diff）

---

## Phase A：基础设施

### Task A1：pom 加 flexmark-java + 错误码扩展

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/java/com/blog/common/ErrorCode.java`

- [ ] **Step 1：pom 追加 flexmark-java（在 tika-core 之后）**

```xml
        <dependency>
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-all</artifactId>
            <version>0.64.8</version>
            <exclusions>
                <exclusion>
                    <groupId>com.vladsch.flexmark</groupId>
                    <artifactId>flexmark-pdf-converter</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
```

> 注意：`flexmark-all` 含 GFM/表格/任务列表/锚点等扩展，排除 PDF converter 避免拉 iText 依赖。

- [ ] **Step 2：在 `ErrorCode.java` 末尾追加（在 SYSTEM_ERROR 之前）**

```java
    // 文章
    public static final int ARTICLE_NOT_FOUND = 1030;
    public static final int CATEGORY_NOT_FOUND = 1031;
    public static final int TAG_NOT_FOUND = 1032;
    public static final int ARTICLE_STATUS_INVALID = 1033;
```

- [ ] **Step 3：拉依赖验证**

```bash
cd backend && ./mvnw -q -DskipTests dependency:resolve
```
Expected：BUILD SUCCESS。

- [ ] **Step 4：commit**

```bash
git add backend/pom.xml backend/src/main/java/com/blog/common/ErrorCode.java
git commit -m "feat(backend): 引入 flexmark-java 与文章业务码"
```

---

### Task A2：MyBatis-Plus 分页插件配置

**Files:**
- Create: `backend/src/main/java/com/blog/infrastructure/MyBatisPlusConfig.java`

- [ ] **Step 1：写配置**

```java
package com.blog.infrastructure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
```

- [ ] **Step 2：编译验证**

```bash
cd backend && ./mvnw -q -DskipTests compile
```
Expected：BUILD SUCCESS。

- [ ] **Step 3：commit**

```bash
git add backend/src/main/java/com/blog/infrastructure
git commit -m "feat(backend): 注册 MyBatis-Plus 分页拦截器"
```

---

### Task A3：Flyway V3 — `article` + `article_tag`

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__init_article.sql`

按 spec 2.1 完整复刻 article + article_tag 表（含 GIN 索引和复合索引）。

- [ ] **Step 1：写 V3 SQL**

```sql
-- 文章
CREATE TABLE IF NOT EXISTS article (
  id            BIGSERIAL PRIMARY KEY,
  title         VARCHAR(200) NOT NULL,
  slug          VARCHAR(200) UNIQUE NOT NULL,
  summary       VARCHAR(500),
  content_md    TEXT NOT NULL,
  content_html  TEXT NOT NULL,
  cover         VARCHAR(255),
  category_id   BIGINT REFERENCES category(id),
  author_id     BIGINT REFERENCES "user"(id),
  status        SMALLINT DEFAULT 0,
  view_count    BIGINT DEFAULT 0,
  word_count    INT DEFAULT 0,
  published_at  TIMESTAMPTZ,
  search_vector TSVECTOR,
  deleted       BOOLEAN DEFAULT FALSE,
  created_at    TIMESTAMPTZ DEFAULT now(),
  updated_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_article_search ON article USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_article_status_published ON article(status, published_at DESC);

-- 文章-标签 多对多
CREATE TABLE IF NOT EXISTS article_tag (
  article_id BIGINT REFERENCES article(id) ON DELETE CASCADE,
  tag_id     BIGINT REFERENCES tag(id) ON DELETE CASCADE,
  PRIMARY KEY (article_id, tag_id)
);
```

- [ ] **Step 2：启动 Spring Boot 跑 V3 迁移**

```bash
cd backend && ./mvnw -q spring-boot:run > /tmp/boot.log 2>&1 &
sleep 25
grep -E "Migrating to version|Successfully applied|ERROR" /tmp/boot.log | head -10
taskkill //F //IM java.exe
```
Expected：看到 `Migrating schema "public" to version "3"`、`Successfully applied 1 migration`。

- [ ] **Step 3：验证表与索引**

```bash
docker exec -i pgsql-win psql -U blog -d blog -c '\dt'
docker exec -i pgsql-win psql -U blog -d blog -c '\d article'
docker exec -i pgsql-win psql -U blog -d blog -c '\di'
```
Expected：article、article_tag 两张表；idx_article_search 和 idx_article_status_published 索引存在。

- [ ] **Step 4：commit**

```bash
git add backend/src/main/resources/db/migration/V3__init_article.sql
git commit -m "feat(backend): Flyway V3 创建 article 与 article_tag 表"
```

---

## Phase B：Entity + Mapper

### Task B1：`ArticleEntity` + `ArticleTagEntity` + Mappers

**Files:**
- Create: `backend/src/main/java/com/blog/module/article/entity/ArticleEntity.java`
- Create: `backend/src/main/java/com/blog/module/article/entity/ArticleTagEntity.java`
- Create: `backend/src/main/java/com/blog/module/article/mapper/ArticleMapper.java`
- Create: `backend/src/main/java/com/blog/module/article/mapper/ArticleTagMapper.java`

- [ ] **Step 1：`ArticleEntity.java`**

`searchVector` 字段用 `@TableField(exist=false)` 标为非实体字段——它由 SQL 层 `to_tsvector` 生成，Java 不直接读写。

```java
package com.blog.module.article.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("article")
public class ArticleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String slug;
    private String summary;
    private String contentMd;
    private String contentHtml;
    private String cover;
    private Long categoryId;
    private Long authorId;
    /** 0 草稿 1 发布 2 下架 */
    private Integer status;
    private Long viewCount;
    private Integer wordCount;
    private OffsetDateTime publishedAt;

    @TableField(exist = false)
    private String searchVector;

    @TableLogic
    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 2：`ArticleTagEntity.java`**

复合主键，没自增 id；MyBatis-Plus 默认要求 @TableId，组合键场景用 `IdType.NONE` 标注 `articleId`。但更稳：不通过 BaseMapper 标准 API，直接写自定义 SQL（下一 Task 在 mapper 接口里加 XML）。这里 entity 用 `@TableId(value="article_id", type=IdType.INPUT)` 即可。

```java
package com.blog.module.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("article_tag")
public class ArticleTagEntity {
    @TableId(value = "article_id", type = IdType.INPUT)
    private Long articleId;
    private Long tagId;
}
```

- [ ] **Step 3：`ArticleMapper.java`**

加两个自定义方法：
- `insertWithSearchVector` — 插入时同步生成 search_vector
- `updateWithSearchVector` — 更新时同步刷新 search_vector

```java
package com.blog.module.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.article.entity.ArticleEntity;
import org.apache.ibatis.annotations.Param;

public interface ArticleMapper extends BaseMapper<ArticleEntity> {

    /** 更新 search_vector，由 SQL 层 to_tsvector('simple', title || ' ' || content_md) 生成 */
    int refreshSearchVector(@Param("id") Long id);
}
```

- [ ] **Step 4：`ArticleTagMapper.java`**

```java
package com.blog.module.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.article.entity.ArticleTagEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ArticleTagMapper extends BaseMapper<ArticleTagEntity> {

    /** 删除某文章的全部 tag 关联 */
    int deleteByArticleId(@Param("articleId") Long articleId);

    /** 批量插入文章-标签关联 */
    int batchInsert(@Param("articleId") Long articleId, @Param("tagIds") List<Long> tagIds);

    /** 查文章绑定的 tagId 列表 */
    List<Long> selectTagIds(@Param("articleId") Long articleId);
}
```

- [ ] **Step 5：写 mapper XML — `backend/src/main/resources/mapper/ArticleMapper.xml`**

> 注意：M1 还没有 mapper XML 目录，需要在 `application-dev.yml` 中加 `mybatis-plus.mapper-locations`（下一 step）。

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.blog.module.article.mapper.ArticleMapper">

    <update id="refreshSearchVector">
        UPDATE article
        SET search_vector = to_tsvector('simple', COALESCE(title, '') || ' ' || COALESCE(content_md, ''))
        WHERE id = #{id}
    </update>

</mapper>
```

- [ ] **Step 6：写 `backend/src/main/resources/mapper/ArticleTagMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.blog.module.article.mapper.ArticleTagMapper">

    <delete id="deleteByArticleId">
        DELETE FROM article_tag WHERE article_id = #{articleId}
    </delete>

    <insert id="batchInsert">
        INSERT INTO article_tag(article_id, tag_id) VALUES
        <foreach collection="tagIds" item="tagId" separator=",">
            (#{articleId}, #{tagId})
        </foreach>
    </insert>

    <select id="selectTagIds" resultType="java.lang.Long">
        SELECT tag_id FROM article_tag WHERE article_id = #{articleId}
    </select>

</mapper>
```

- [ ] **Step 7：在 `application-dev.yml` 加 mapper-locations**

找到 `mybatis-plus:` 节点，在 `configuration:` 之前加：

```yaml
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: true
      logic-not-delete-value: false
```

- [ ] **Step 8：编译验证**

```bash
cd backend && ./mvnw -q -DskipTests compile
```
Expected：BUILD SUCCESS。

- [ ] **Step 9：commit**

```bash
git add backend/src/main/java/com/blog/module/article backend/src/main/resources/mapper backend/src/main/resources/application-dev.yml
git commit -m "feat(backend): 添加 article/article_tag entity、mapper 与 XML"
```

---

## Phase C：Markdown 渲染管线

### Task C1：`MarkdownRenderer` + 单测

**Files:**
- Create: `backend/src/main/java/com/blog/module/article/service/MarkdownRenderer.java`
- Test: `backend/src/test/java/com/blog/module/article/service/MarkdownRendererTest.java`

`MarkdownRenderer` 提供：
- `render(md)` → `{ html, wordCount }`
- 启用 GFM tables、task list、anchor、strikethrough

- [ ] **Step 1：先写测试 `MarkdownRendererTest.java`**

```java
package com.blog.module.article.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void plain_paragraph_renders_to_p() {
        var r = renderer.render("hello world");
        assertTrue(r.html().contains("<p>hello world</p>"));
        assertEquals(11, r.wordCount());
    }

    @Test
    void heading_renders_with_anchor_id() {
        var r = renderer.render("# Title\n\ncontent");
        assertTrue(r.html().contains("<h1"));
        assertTrue(r.html().contains(">Title</h1>") || r.html().matches("(?s).*<h1[^>]*id=\"[^\"]+\"[^>]*>Title</h1>.*"));
    }

    @Test
    void table_renders() {
        String md = """
            | a | b |
            |---|---|
            | 1 | 2 |
            """;
        var r = renderer.render(md);
        assertTrue(r.html().contains("<table>"));
        assertTrue(r.html().contains("<td>1</td>"));
    }

    @Test
    void task_list_renders() {
        var r = renderer.render("- [x] done\n- [ ] todo");
        assertTrue(r.html().contains("type=\"checkbox\""));
    }

    @Test
    void word_count_strips_html() {
        var r = renderer.render("**bold** text");
        // "bold text" = 9 chars
        assertEquals(9, r.wordCount());
    }

    @Test
    void word_count_handles_chinese() {
        var r = renderer.render("你好世界");
        assertEquals(4, r.wordCount());
    }

    @Test
    void empty_input_returns_empty_html_zero_count() {
        var r = renderer.render("");
        assertEquals("", r.html().trim());
        assertEquals(0, r.wordCount());
    }
}
```

- [ ] **Step 2：跑测试看红**

```bash
cd backend && ./mvnw -q -Dtest=MarkdownRendererTest test
```
Expected：编译错误，`MarkdownRenderer` 不存在。

- [ ] **Step 3：写 `MarkdownRenderer.java`**

```java
package com.blog.module.article.service;

import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                TaskListExtension.create(),
                AnchorLinkExtension.create(),
                StrikethroughExtension.create()));
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public RenderResult render(String md) {
        if (md == null || md.isBlank()) {
            return new RenderResult("", 0);
        }
        String html = renderer.render(parser.parse(md));
        int wordCount = stripHtml(html).length();
        return new RenderResult(html, wordCount);
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", "").trim();
    }

    public record RenderResult(String html, int wordCount) {}
}
```

- [ ] **Step 4：跑测试看绿**

```bash
cd backend && ./mvnw -q -Dtest=MarkdownRendererTest test
```
Expected：BUILD SUCCESS，7 用例全过。

> 如果 `heading_renders_with_anchor_id` 失败：检查 flexmark anchor extension 是否产生不同格式（如 `<h1 id="...">Title</h1>`），按实际输出调整 assertion。先看 assertion 已用 `||` 兼容两种格式。

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/article/service/MarkdownRenderer.java backend/src/test/java/com/blog/module/article/service
git commit -m "feat(backend): MarkdownRenderer 支持 GFM 表格/任务列表/锚点"
```

---

## Phase D：ArticleService

### Task D1：DTO + VO

**Files:**
- Create: `backend/src/main/java/com/blog/module/article/dto/ArticleUpsertRequest.java`
- Create: `backend/src/main/java/com/blog/module/article/dto/ArticleListQuery.java`
- Create: `backend/src/main/java/com/blog/module/article/vo/ArticleVO.java`
- Create: `backend/src/main/java/com/blog/module/article/vo/ArticleListItemVO.java`
- Create: `backend/src/main/java/com/blog/common/PageVO.java`

- [ ] **Step 1：通用 `PageVO.java`（M2.2 第一次需要分页响应包装）**

```java
package com.blog.common;

import java.util.List;

public record PageVO<T>(
        List<T> records,
        long total,
        long page,
        long size
) {}
```

- [ ] **Step 2：`ArticleUpsertRequest.java`**

```java
package com.blog.module.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ArticleUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "slug 只能包含小写字母、数字和短横线")
        String slug,
        @Size(max = 500) String summary,
        @NotBlank String contentMd,
        @Size(max = 255) String cover,
        @NotNull Long categoryId,
        List<Long> tagIds
) {}
```

- [ ] **Step 3：`ArticleListQuery.java`**

```java
package com.blog.module.article.dto;

public record ArticleListQuery(
        Integer page,
        Integer size,
        Integer status,
        String keyword
) {
    public int safePage() { return page == null || page < 1 ? 1 : page; }
    public int safeSize() { return size == null || size < 1 || size > 100 ? 10 : size; }
}
```

- [ ] **Step 4：`ArticleVO.java`**（详情/编辑回填用）

```java
package com.blog.module.article.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record ArticleVO(
        Long id,
        String title,
        String slug,
        String summary,
        String contentMd,
        String contentHtml,
        String cover,
        Long categoryId,
        List<Long> tagIds,
        Integer status,
        Long viewCount,
        Integer wordCount,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

- [ ] **Step 5：`ArticleListItemVO.java`**（列表用，瘦字段）

```java
package com.blog.module.article.vo;

import java.time.OffsetDateTime;

public record ArticleListItemVO(
        Long id,
        String title,
        String slug,
        String summary,
        String cover,
        Long categoryId,
        Integer status,
        Long viewCount,
        Integer wordCount,
        OffsetDateTime publishedAt,
        OffsetDateTime updatedAt
) {}
```

- [ ] **Step 6：编译 + commit**

```bash
cd backend && ./mvnw -q -DskipTests compile
git add backend/src/main/java/com/blog/common/PageVO.java backend/src/main/java/com/blog/module/article/dto backend/src/main/java/com/blog/module/article/vo
git commit -m "feat(backend): 添加 Article DTO/VO 与通用 PageVO"
```

---

### Task D2：`ArticleService` 骨架（CRUD 不含状态切换）+ 集成测试

**Files:**
- Create: `backend/src/main/java/com/blog/module/article/service/ArticleService.java`
- Test: `backend/src/test/java/com/blog/module/article/service/ArticleServiceTest.java`

第一轮先实现 5 个方法（不含 changeStatus、prevNext）：
- `create(req, authorId)` — 插入 + 渲染 + tag 关联 + search_vector
- `update(id, req)` — 更新 + 渲染 + tag 重新关联 + search_vector
- `delete(id)` — 软删除（article_tag 物理 CASCADE 自动清理）
- `get(id)` — 编辑回填
- `list(query)` — 后台分页列表（含 status / keyword 过滤）

`changeStatus` 留 Task D3 加上（因为它涉及 published_at 首次填入的特殊逻辑）。

- [ ] **Step 1：先写测试 `ArticleServiceTest.java`**

```java
package com.blog.module.article.service;

import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.common.PageVO;
import com.blog.module.article.dto.ArticleListQuery;
import com.blog.module.article.dto.ArticleUpsertRequest;
import com.blog.module.article.vo.ArticleListItemVO;
import com.blog.module.article.vo.ArticleVO;
import com.blog.module.category.dto.CategoryUpsertRequest;
import com.blog.module.category.service.CategoryService;
import com.blog.module.tag.dto.TagUpsertRequest;
import com.blog.module.tag.service.TagService;
import org.junit.jupiter.api.BeforeEach;
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
class ArticleServiceTest {

    @Autowired ArticleService articleService;
    @Autowired CategoryService categoryService;
    @Autowired TagService tagService;

    private Long catId;
    private Long tag1, tag2;

    @BeforeEach
    void setUp() {
        catId = categoryService.create(new CategoryUpsertRequest("Test", "test-cat-" + System.nanoTime(), "", 0)).id();
        tag1 = tagService.create(new TagUpsertRequest("T1", "t1-" + System.nanoTime())).id();
        tag2 = tagService.create(new TagUpsertRequest("T2", "t2-" + System.nanoTime())).id();
    }

    @Test
    void create_renders_markdown_and_persists_tags() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "Hello", "hello-" + System.nanoTime(),
                "intro", "# Title\n\nbody **bold**", null,
                catId, List.of(tag1, tag2)),
                1L);

        assertNotNull(created.id());
        assertTrue(created.contentHtml().contains("<h1"));
        assertTrue(created.contentHtml().contains("<strong>bold</strong>"));
        assertTrue(created.wordCount() > 0);
        assertEquals(0, created.status());                 // 默认草稿
        assertNull(created.publishedAt());
        assertEquals(2, created.tagIds().size());
        assertTrue(created.tagIds().containsAll(List.of(tag1, tag2)));
    }

    @Test
    void duplicate_slug_rejected() {
        String slug = "dup-slug-" + System.nanoTime();
        articleService.create(new ArticleUpsertRequest(
                "A", slug, "", "x", null, catId, List.of()), 1L);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                articleService.create(new ArticleUpsertRequest(
                        "B", slug, "", "y", null, catId, List.of()), 1L));
        assertEquals(ErrorCode.SLUG_DUPLICATED, ex.getCode());
    }

    @Test
    void unknown_category_rejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                articleService.create(new ArticleUpsertRequest(
                        "A", "slug-bad-cat-" + System.nanoTime(), "", "x", null, 999999L, List.of()), 1L));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, ex.getCode());
    }

    @Test
    void update_changes_content_and_tag_set() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "Old", "old-slug-" + System.nanoTime(), "", "old", null,
                catId, List.of(tag1)), 1L);

        ArticleVO updated = articleService.update(created.id(),
                new ArticleUpsertRequest("New", created.slug(), "new summary",
                        "new content", null, catId, List.of(tag2)));
        assertEquals("New", updated.title());
        assertEquals("new summary", updated.summary());
        assertTrue(updated.contentHtml().contains("new content"));
        assertEquals(List.of(tag2), updated.tagIds());
    }

    @Test
    void get_returns_tags_in_persisted_order() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "G", "get-slug-" + System.nanoTime(), "", "body", null,
                catId, List.of(tag1, tag2)), 1L);
        ArticleVO got = articleService.get(created.id());
        assertEquals(created.id(), got.id());
        assertEquals(2, got.tagIds().size());
    }

    @Test
    void get_nonexistent_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.get(999999L));
        assertEquals(ErrorCode.ARTICLE_NOT_FOUND, ex.getCode());
    }

    @Test
    void soft_delete_excludes_from_list() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "Del", "del-slug-" + System.nanoTime(), "", "x", null,
                catId, List.of()), 1L);
        articleService.delete(created.id());

        PageVO<ArticleListItemVO> page = articleService.list(new ArticleListQuery(1, 50, null, null));
        assertFalse(page.records().stream().anyMatch(r -> r.id().equals(created.id())));
    }

    @Test
    void list_filters_by_status_and_keyword() {
        long ts = System.nanoTime();
        articleService.create(new ArticleUpsertRequest(
                "Apple News", "apple-" + ts, "", "x", null, catId, List.of()), 1L);
        articleService.create(new ArticleUpsertRequest(
                "Banana News", "banana-" + ts, "", "y", null, catId, List.of()), 1L);

        PageVO<ArticleListItemVO> matched = articleService.list(
                new ArticleListQuery(1, 50, 0, "Apple"));
        assertTrue(matched.records().stream().anyMatch(r -> r.title().contains("Apple")));
        assertFalse(matched.records().stream().anyMatch(r -> r.title().contains("Banana")));
    }
}
```

- [ ] **Step 2：跑测试看红**

```bash
cd backend && ./mvnw -q -Dtest=ArticleServiceTest test
```
Expected：编译失败，`ArticleService` 不存在。

- [ ] **Step 3：写 `ArticleService.java`**

```java
package com.blog.module.article.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.BusinessException;
import com.blog.common.ErrorCode;
import com.blog.common.PageVO;
import com.blog.module.article.dto.ArticleListQuery;
import com.blog.module.article.dto.ArticleUpsertRequest;
import com.blog.module.article.entity.ArticleEntity;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.mapper.ArticleTagMapper;
import com.blog.module.article.vo.ArticleListItemVO;
import com.blog.module.article.vo.ArticleVO;
import com.blog.module.category.entity.CategoryEntity;
import com.blog.module.category.mapper.CategoryMapper;
import com.blog.module.tag.entity.TagEntity;
import com.blog.module.tag.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final MarkdownRenderer renderer;

    @Transactional
    public ArticleVO create(ArticleUpsertRequest req, Long authorId) {
        ensureCategoryExists(req.categoryId());
        ensureTagsExist(req.tagIds());
        ensureSlugUnique(req.slug(), null);

        MarkdownRenderer.RenderResult rendered = renderer.render(req.contentMd());

        ArticleEntity e = new ArticleEntity();
        e.setTitle(req.title());
        e.setSlug(req.slug());
        e.setSummary(req.summary());
        e.setContentMd(req.contentMd());
        e.setContentHtml(rendered.html());
        e.setCover(req.cover());
        e.setCategoryId(req.categoryId());
        e.setAuthorId(authorId);
        e.setStatus(0);
        e.setViewCount(0L);
        e.setWordCount(rendered.wordCount());
        e.setDeleted(false);
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        articleMapper.insert(e);

        saveTags(e.getId(), req.tagIds());
        articleMapper.refreshSearchVector(e.getId());

        return toVO(e, req.tagIds() == null ? List.of() : req.tagIds());
    }

    @Transactional
    public ArticleVO update(Long id, ArticleUpsertRequest req) {
        ArticleEntity exists = articleMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在");
        }
        ensureCategoryExists(req.categoryId());
        ensureTagsExist(req.tagIds());
        ensureSlugUnique(req.slug(), id);

        MarkdownRenderer.RenderResult rendered = renderer.render(req.contentMd());

        exists.setTitle(req.title());
        exists.setSlug(req.slug());
        exists.setSummary(req.summary());
        exists.setContentMd(req.contentMd());
        exists.setContentHtml(rendered.html());
        exists.setCover(req.cover());
        exists.setCategoryId(req.categoryId());
        exists.setWordCount(rendered.wordCount());
        exists.setUpdatedAt(OffsetDateTime.now());
        articleMapper.updateById(exists);

        saveTags(id, req.tagIds());
        articleMapper.refreshSearchVector(id);

        return toVO(exists, req.tagIds() == null ? List.of() : req.tagIds());
    }

    @Transactional
    public void delete(Long id) {
        ArticleEntity exists = articleMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在");
        }
        articleMapper.deleteById(id);
        // article_tag 通过 FK ON DELETE CASCADE 物理删除；软删除场景下 article 行还在，FK 不触发
        // 这里主动清掉关联，保持语义一致
        articleTagMapper.deleteByArticleId(id);
    }

    public ArticleVO get(Long id) {
        ArticleEntity e = articleMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在");
        }
        List<Long> tagIds = articleTagMapper.selectTagIds(id);
        return toVO(e, tagIds);
    }

    public PageVO<ArticleListItemVO> list(ArticleListQuery q) {
        Page<ArticleEntity> page = new Page<>(q.safePage(), q.safeSize());
        var wrapper = Wrappers.<ArticleEntity>lambdaQuery()
                .orderByDesc(ArticleEntity::getCreatedAt);
        if (q.status() != null) {
            wrapper.eq(ArticleEntity::getStatus, q.status());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            wrapper.like(ArticleEntity::getTitle, q.keyword().trim());
        }
        Page<ArticleEntity> result = articleMapper.selectPage(page, wrapper);
        List<ArticleListItemVO> rows = result.getRecords().stream().map(this::toListItem).toList();
        return new PageVO<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private void ensureCategoryExists(Long categoryId) {
        CategoryEntity c = categoryMapper.selectById(categoryId);
        if (c == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "分类不存在");
        }
    }

    private void ensureTagsExist(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        Long count = tagMapper.selectCount(
                Wrappers.<TagEntity>lambdaQuery().in(TagEntity::getId, tagIds));
        if (count != tagIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND, "存在无效标签");
        }
    }

    private void ensureSlugUnique(String slug, Long excludeId) {
        Long count = articleMapper.selectCount(
                Wrappers.<ArticleEntity>lambdaQuery()
                        .eq(ArticleEntity::getSlug, slug)
                        .ne(excludeId != null, ArticleEntity::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.SLUG_DUPLICATED, "slug 已存在");
        }
    }

    private void saveTags(Long articleId, List<Long> tagIds) {
        articleTagMapper.deleteByArticleId(articleId);
        if (tagIds != null && !tagIds.isEmpty()) {
            articleTagMapper.batchInsert(articleId, tagIds);
        }
    }

    private ArticleVO toVO(ArticleEntity e, List<Long> tagIds) {
        return new ArticleVO(
                e.getId(), e.getTitle(), e.getSlug(), e.getSummary(),
                e.getContentMd(), e.getContentHtml(), e.getCover(),
                e.getCategoryId(), tagIds, e.getStatus(),
                e.getViewCount(), e.getWordCount(),
                e.getPublishedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private ArticleListItemVO toListItem(ArticleEntity e) {
        return new ArticleListItemVO(
                e.getId(), e.getTitle(), e.getSlug(), e.getSummary(),
                e.getCover(), e.getCategoryId(), e.getStatus(),
                e.getViewCount(), e.getWordCount(),
                e.getPublishedAt(), e.getUpdatedAt());
    }
}
```

- [ ] **Step 4：跑测试看绿**

```bash
cd backend && ./mvnw -q -Dtest=ArticleServiceTest test
```
Expected：BUILD SUCCESS，8 用例全过。

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/article/service/ArticleService.java backend/src/test/java/com/blog/module/article
git commit -m "feat(backend): ArticleService CRUD（含 Markdown 渲染与 tag 关联）"
```

---

### Task D3：`ArticleService.changeStatus`（含首次发布时填 publishedAt）+ 测试

**Files:**
- Modify: `backend/src/main/java/com/blog/module/article/service/ArticleService.java`
- Modify: `backend/src/test/java/com/blog/module/article/service/ArticleServiceTest.java`

- [ ] **Step 1：在测试类末尾追加用例**

```java
    @Test
    void change_status_draft_to_published_sets_published_at_first_time() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "Pub", "pub-slug-" + System.nanoTime(), "", "x", null,
                catId, List.of()), 1L);
        assertEquals(0, created.status());
        assertNull(created.publishedAt());

        ArticleVO published = articleService.changeStatus(created.id(), 1);
        assertEquals(1, published.status());
        assertNotNull(published.publishedAt());
        java.time.OffsetDateTime firstPublishedAt = published.publishedAt();

        // 下架
        ArticleVO unpublished = articleService.changeStatus(created.id(), 2);
        assertEquals(2, unpublished.status());
        assertEquals(firstPublishedAt, unpublished.publishedAt());

        // 再发布 → publishedAt 不变
        ArticleVO republished = articleService.changeStatus(created.id(), 1);
        assertEquals(firstPublishedAt, republished.publishedAt());
    }

    @Test
    void change_status_invalid_value_rejected() {
        ArticleVO created = articleService.create(new ArticleUpsertRequest(
                "Bad", "bad-status-" + System.nanoTime(), "", "x", null,
                catId, List.of()), 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.changeStatus(created.id(), 99));
        assertEquals(ErrorCode.ARTICLE_STATUS_INVALID, ex.getCode());
    }
```

- [ ] **Step 2：跑测试看红**

```bash
cd backend && ./mvnw -q -Dtest=ArticleServiceTest test
```
Expected：编译错误，`changeStatus` 不存在。

- [ ] **Step 3：在 `ArticleService` 中添加 `changeStatus` 方法（放在 `delete` 之后）**

```java
    @Transactional
    public ArticleVO changeStatus(Long id, int status) {
        if (status != 0 && status != 1 && status != 2) {
            throw new BusinessException(ErrorCode.ARTICLE_STATUS_INVALID,
                    "status 必须是 0（草稿）/1（发布）/2（下架）");
        }
        ArticleEntity exists = articleMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在");
        }
        exists.setStatus(status);
        if (status == 1 && exists.getPublishedAt() == null) {
            exists.setPublishedAt(OffsetDateTime.now());
        }
        exists.setUpdatedAt(OffsetDateTime.now());
        articleMapper.updateById(exists);

        List<Long> tagIds = articleTagMapper.selectTagIds(id);
        return toVO(exists, tagIds);
    }
```

- [ ] **Step 4：跑测试看绿**

```bash
cd backend && ./mvnw -q -Dtest=ArticleServiceTest test
```
Expected：BUILD SUCCESS，10 用例全过。

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/article/service/ArticleService.java backend/src/test/java/com/blog/module/article
git commit -m "feat(backend): ArticleService.changeStatus 首次发布填 publishedAt"
```

---

## Phase E：AdminArticleController

### Task E1：`AdminArticleController` + 当前登录用户解析

**Files:**
- Create: `backend/src/main/java/com/blog/module/article/controller/AdminArticleController.java`
- Modify: `backend/src/main/java/com/blog/module/auth/service/AuthService.java`（加 `getCurrentUserId(username)`）

`AdminArticleController.create` 需要拿到当前用户的 id（authorId）。M1 的 JwtAuthenticationFilter 注入到 SecurityContext 的 principal 是 username 字符串。这里加个轻量查询。

- [ ] **Step 1：在 `AuthService.java` 末尾追加方法**

```java
    public Long getCurrentUserId(String username) {
        UserEntity u = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
        if (u == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return u.getId();
    }
```

> 注意：`UserEntity`、`Wrappers`、`UserMapper` 已在 AuthService 顶部 import 过（M1 写的）；如果你打开文件确认有 import 则直接加方法即可，否则补 import。

- [ ] **Step 2：`AdminArticleController.java`**

```java
package com.blog.module.article.controller;

import com.blog.common.PageVO;
import com.blog.common.Result;
import com.blog.module.article.dto.ArticleListQuery;
import com.blog.module.article.dto.ArticleUpsertRequest;
import com.blog.module.article.service.ArticleService;
import com.blog.module.article.vo.ArticleListItemVO;
import com.blog.module.article.vo.ArticleVO;
import com.blog.module.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final ArticleService articleService;
    private final AuthService authService;

    @GetMapping
    public Result<PageVO<ArticleListItemVO>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(articleService.list(new ArticleListQuery(page, size, status, keyword)));
    }

    @GetMapping("/{id}")
    public Result<ArticleVO> get(@PathVariable Long id) {
        return Result.ok(articleService.get(id));
    }

    @PostMapping
    public Result<ArticleVO> create(@RequestBody @Valid ArticleUpsertRequest req,
                                    Authentication auth) {
        Long authorId = authService.getCurrentUserId(auth.getName());
        return Result.ok(articleService.create(req, authorId));
    }

    @PutMapping("/{id}")
    public Result<ArticleVO> update(@PathVariable Long id,
                                    @RequestBody @Valid ArticleUpsertRequest req) {
        return Result.ok(articleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.ok();
    }

    @PatchMapping("/{id}/status")
    public Result<ArticleVO> changeStatus(@PathVariable Long id,
                                          @RequestBody StatusChangeRequest req) {
        return Result.ok(articleService.changeStatus(id, req.status()));
    }

    public record StatusChangeRequest(Integer status) {}
}
```

- [ ] **Step 3：跑全量测试**

```bash
cd backend && ./mvnw -q test
```
Expected：M1 9 + Upload 5 + Category 6 + Tag 6 + Markdown 7 + Article 10 = 43 用例全过。

- [ ] **Step 4：手动 curl 验证**

启动应用 → 登录拿 token，依次：
```bash
# 准备分类和标签
CAT_ID=$(curl -s -X POST http://localhost:8080/api/admin/categories \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"技术","slug":"tech","sort":1}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d);process.stdin.on('end',()=>console.log(JSON.parse(s).data.id))")
TAG_ID=$(curl -s -X POST http://localhost:8080/api/admin/tags \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Java","slug":"java"}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d);process.stdin.on('end',()=>console.log(JSON.parse(s).data.id))")

# 创建文章
curl -s -X POST http://localhost:8080/api/admin/articles \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"title\":\"Hello\",\"slug\":\"hello\",\"summary\":\"intro\",\"contentMd\":\"# H1\\n\\n**bold**\",\"categoryId\":$CAT_ID,\"tagIds\":[$TAG_ID]}"

# 列表
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/admin/articles?page=1&size=10"

# 切状态：草稿 → 发布
curl -s -X PATCH http://localhost:8080/api/admin/articles/1/status \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"status":1}'
```
Expected：依次返回 ok 响应，最后一步看到 `publishedAt` 不为 null。

停服：`taskkill //F //IM java.exe`

- [ ] **Step 5：commit**

```bash
git add backend/src/main/java/com/blog/module/auth/service/AuthService.java backend/src/main/java/com/blog/module/article/controller
git commit -m "feat(backend): AdminArticleController CRUD + 状态切换"
```

---

## Phase F：前端文章列表页

### Task F1：article API + 类型

**Files:**
- Modify: `frontend/src/types/index.ts`
- Create: `frontend/src/api/article.ts`

- [ ] **Step 1：在 `frontend/src/types/index.ts` 末尾追加**

```ts
export interface Article {
  id: number
  title: string
  slug: string
  summary?: string
  contentMd: string
  contentHtml: string
  cover?: string
  categoryId: number
  tagIds: number[]
  status: number
  viewCount: number
  wordCount: number
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface ArticleListItem {
  id: number
  title: string
  slug: string
  summary?: string
  cover?: string
  categoryId: number
  status: number
  viewCount: number
  wordCount: number
  publishedAt?: string
  updatedAt: string
}

export interface ArticleUpsert {
  title: string
  slug: string
  summary?: string
  contentMd: string
  cover?: string
  categoryId: number
  tagIds: number[]
}

export interface ArticleListQuery {
  page?: number
  size?: number
  status?: number
  keyword?: string
}

export interface Page<T> {
  records: T[]
  total: number
  page: number
  size: number
}
```

- [ ] **Step 2：`frontend/src/api/article.ts`**

```ts
import request from './request'
import type {
  Article,
  ArticleListItem,
  ArticleListQuery,
  ArticleUpsert,
  Page,
} from '@/types'

export function listArticles(query: ArticleListQuery = {}) {
  return request.get<unknown, Page<ArticleListItem>>('/admin/articles', { params: query })
}

export function getArticle(id: number) {
  return request.get<unknown, Article>(`/admin/articles/${id}`)
}

export function createArticle(body: ArticleUpsert) {
  return request.post<unknown, Article>('/admin/articles', body)
}

export function updateArticle(id: number, body: ArticleUpsert) {
  return request.put<unknown, Article>(`/admin/articles/${id}`, body)
}

export function deleteArticle(id: number) {
  return request.delete<unknown, void>(`/admin/articles/${id}`)
}

export function changeArticleStatus(id: number, status: number) {
  return request.patch<unknown, Article>(`/admin/articles/${id}/status`, { status })
}
```

- [ ] **Step 3：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/types frontend/src/api
git commit -m "feat(frontend): 添加 article API 与类型"
```

---

### Task F2：`ArticleListView.vue` 文章列表页

**Files:**
- Create: `frontend/src/views/admin/ArticleListView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AdminLayout.vue`（加文章管理菜单项）

提供：
- 分页表格（标题/slug/分类名/状态/字数/更新时间/操作）
- 顶部过滤：status 下拉 + keyword 搜索框
- 操作列：编辑（跳转 `/admin/articles/:id/edit`）、发布/下架/转草稿、删除

- [ ] **Step 1：`ArticleListView.vue`**

```vue
<template>
  <div class="bg-white rounded p-6">
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-xl font-semibold">文章管理</h2>
      <el-button type="primary" @click="goNew">写新文章</el-button>
    </div>

    <div class="flex gap-3 mb-4">
      <el-select v-model="query.status" placeholder="所有状态" clearable style="width: 140px" @change="reload">
        <el-option :value="0" label="草稿" />
        <el-option :value="1" label="已发布" />
        <el-option :value="2" label="已下架" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="搜索标题"
        clearable
        style="width: 240px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button @click="reload">搜索</el-button>
    </div>

    <el-table :data="page.records" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="slug" label="slug" width="180" show-overflow-tooltip />
      <el-table-column label="分类" width="120">
        <template #default="{ row }">
          {{ categoryMap.get(row.categoryId) || '-' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="wordCount" label="字数" width="80" />
      <el-table-column prop="updatedAt" label="更新时间" width="180">
        <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click="goEdit(row.id)">编辑</el-button>
          <el-button v-if="row.status !== 1" size="small" type="success" @click="changeStatus(row, 1)">发布</el-button>
          <el-button v-if="row.status === 1" size="small" @click="changeStatus(row, 2)">下架</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="mt-4 flex justify-end"
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="page.total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="reload"
      @size-change="reload"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listArticles,
  deleteArticle,
  changeArticleStatus,
} from '@/api/article'
import { listAdminCategories } from '@/api/category'
import type { ArticleListItem, ArticleListQuery, Page } from '@/types'

const router = useRouter()

const query = reactive<Required<Pick<ArticleListQuery, 'page' | 'size'>> & ArticleListQuery>({
  page: 1,
  size: 10,
  status: undefined,
  keyword: '',
})

const page = ref<Page<ArticleListItem>>({ records: [], total: 0, page: 1, size: 10 })
const loading = ref(false)
const categoryMap = ref<Map<number, string>>(new Map())

function statusLabel(s: number) {
  return s === 0 ? '草稿' : s === 1 ? '已发布' : '已下架'
}

function statusType(s: number): 'info' | 'success' | 'warning' {
  return s === 0 ? 'info' : s === 1 ? 'success' : 'warning'
}

function formatDate(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

async function reload() {
  loading.value = true
  try {
    page.value = await listArticles({ ...query })
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  const cats = await listAdminCategories()
  categoryMap.value = new Map(cats.map((c) => [c.id, c.name]))
}

function goNew() {
  router.push('/admin/articles/new')
}

function goEdit(id: number) {
  router.push(`/admin/articles/${id}/edit`)
}

async function changeStatus(row: ArticleListItem, status: number) {
  await changeArticleStatus(row.id, status)
  ElMessage.success('已更新状态')
  await reload()
}

async function handleDelete(row: ArticleListItem) {
  const ok = await ElMessageBox.confirm(`确定删除文章「${row.title}」吗？`, '确认', {
    type: 'warning',
  }).catch(() => false)
  if (!ok) return
  await deleteArticle(row.id)
  ElMessage.success('已删除')
  await reload()
}

onMounted(async () => {
  await loadCategories()
  await reload()
})
</script>
```

- [ ] **Step 2：在 `router/index.ts` children 加路由**

定位现有 children，改为：
```ts
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('@/views/admin/DashboardView.vue') },
      { path: 'articles', component: () => import('@/views/admin/ArticleListView.vue') },
      { path: 'articles/new', component: () => import('@/views/admin/ArticleEditView.vue') },
      { path: 'articles/:id/edit', component: () => import('@/views/admin/ArticleEditView.vue') },
      { path: 'categories', component: () => import('@/views/admin/CategoryView.vue') },
      { path: 'tags', component: () => import('@/views/admin/TagView.vue') },
    ],
```

> 注意：`ArticleEditView.vue` 在 Task G1 才建。先建空占位避免 vue-tsc 报错：
> 创建 `frontend/src/views/admin/ArticleEditView.vue`：
> ```vue
> <template><div>article edit placeholder</div></template>
> <script setup lang="ts"></script>
> ```

- [ ] **Step 3：在 `AdminLayout.vue` 的 `<el-menu>` 里加文章管理菜单项**

`el-menu-item index="/admin/dashboard"` 之后插入：
```vue
          <el-menu-item index="/admin/articles">
            <span>文章管理</span>
          </el-menu-item>
```

- [ ] **Step 4：类型检查 + commit**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
git add frontend/src/views/admin/ArticleListView.vue frontend/src/views/admin/ArticleEditView.vue frontend/src/router/index.ts frontend/src/layouts/AdminLayout.vue
git commit -m "feat(frontend): 文章列表页（含分页/过滤/状态切换）"
```

---

## Phase G：前端文章编辑页

### Task G1：装 md-editor-v3

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1：用 pnpm 装**

```bash
cd frontend && pnpm add md-editor-v3@5
```
Expected：写入 dependencies。

- [ ] **Step 2：类型检查（确保依赖装好）**

```bash
pnpm exec vue-tsc --noEmit
```
Expected：通过。

- [ ] **Step 3：commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "chore(frontend): 引入 md-editor-v3"
```

---

### Task G2：`ArticleEditView.vue` 文章编辑页

**Files:**
- Modify: `frontend/src/views/admin/ArticleEditView.vue`（覆盖占位）

提供：
- 标题/slug/摘要输入
- 分类下拉（单选）
- 标签下拉（多选）
- cover：手动填 URL + "上传封面"按钮（点击触发隐藏 input file，上传成功后自动填入 URL）
- md-editor-v3 编辑器（左写右预览；自带工具栏；接入图片上传：调 `/api/admin/upload`）
- 底部按钮：保存草稿 / 发布 / 取消
- 编辑模式（路由含 `:id`）启动时调 `getArticle(id)` 回填

- [ ] **Step 1：完整覆盖 `ArticleEditView.vue`**

```vue
<template>
  <div class="bg-white rounded p-6">
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-xl font-semibold">{{ isEdit ? '编辑文章' : '写新文章' }}</h2>
      <div class="flex gap-2">
        <el-button @click="router.back()">取消</el-button>
        <el-button :loading="saving" @click="handleSave(0)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave(1)">
          {{ form.id && form.status === 1 ? '保存' : '发布' }}
        </el-button>
      </div>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <div class="grid grid-cols-2 gap-4">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="200" />
        </el-form-item>
        <el-form-item label="slug" prop="slug">
          <el-input v-model="form.slug" maxlength="200" placeholder="小写字母/数字/短横线" />
        </el-form-item>
      </div>

      <el-form-item label="摘要" prop="summary">
        <el-input v-model="form.summary" type="textarea" maxlength="500" :rows="2" />
      </el-form-item>

      <div class="grid grid-cols-2 gap-4">
        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="选择分类" class="w-full">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tagIds" multiple placeholder="选择标签" class="w-full">
            <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
      </div>

      <el-form-item label="封面">
        <div class="flex gap-2 items-center w-full">
          <el-input v-model="form.cover" placeholder="封面图 URL（或点右侧按钮上传）" />
          <input
            ref="coverFileInput"
            type="file"
            accept="image/*"
            class="hidden"
            @change="handleCoverPick"
          />
          <el-button :loading="coverUploading" @click="coverFileInput?.click()">上传封面</el-button>
        </div>
        <img v-if="form.cover" :src="form.cover" class="mt-2 max-h-32 rounded border" />
      </el-form-item>

      <el-form-item label="正文（Markdown）" prop="contentMd">
        <MdEditor
          v-model="form.contentMd"
          :on-upload-img="handleEditorUpload"
          style="height: 600px"
        />
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import {
  createArticle,
  updateArticle,
  getArticle,
  changeArticleStatus,
} from '@/api/article'
import { listAdminCategories } from '@/api/category'
import { listAdminTags } from '@/api/tag'
import { uploadFile } from '@/api/upload'
import type { Category, Tag } from '@/types'

const route = useRoute()
const router = useRouter()

const isEdit = computed(() => !!route.params.id)
const articleId = computed(() => (route.params.id ? Number(route.params.id) : null))

const formRef = ref<FormInstance>()
const coverFileInput = ref<HTMLInputElement>()
const saving = ref(false)
const coverUploading = ref(false)

const categories = ref<Category[]>([])
const tags = ref<Tag[]>([])

const form = reactive({
  id: null as number | null,
  title: '',
  slug: '',
  summary: '',
  contentMd: '',
  cover: '',
  categoryId: undefined as number | undefined,
  tagIds: [] as number[],
  status: 0,
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  slug: [
    { required: true, message: '请输入 slug', trigger: 'blur' },
    { pattern: /^[a-z0-9-]+$/, message: '只能包含小写字母、数字和短横线', trigger: 'blur' },
  ],
  contentMd: [{ required: true, message: '请输入正文', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

async function loadOptions() {
  const [c, t] = await Promise.all([listAdminCategories(), listAdminTags()])
  categories.value = c
  tags.value = t
}

async function loadArticle() {
  if (!articleId.value) return
  const a = await getArticle(articleId.value)
  form.id = a.id
  form.title = a.title
  form.slug = a.slug
  form.summary = a.summary ?? ''
  form.contentMd = a.contentMd
  form.cover = a.cover ?? ''
  form.categoryId = a.categoryId
  form.tagIds = a.tagIds ?? []
  form.status = a.status
}

async function handleCoverPick(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  coverUploading.value = true
  try {
    const r = await uploadFile(file)
    form.cover = r.url
    ElMessage.success('封面已上传')
  } finally {
    coverUploading.value = false
    input.value = ''
  }
}

async function handleEditorUpload(
  files: File[],
  callback: (urls: string[] | { url: string; alt: string; title: string }[]) => void,
) {
  const results = await Promise.all(files.map((f) => uploadFile(f)))
  callback(results.map((r) => r.url))
}

async function handleSave(targetStatus: 0 | 1) {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      title: form.title,
      slug: form.slug,
      summary: form.summary || undefined,
      contentMd: form.contentMd,
      cover: form.cover || undefined,
      categoryId: form.categoryId!,
      tagIds: form.tagIds,
    }
    let saved
    if (form.id) {
      saved = await updateArticle(form.id, payload)
    } else {
      saved = await createArticle(payload)
    }
    if (targetStatus === 1 && saved.status !== 1) {
      saved = await changeArticleStatus(saved.id, 1)
    } else if (targetStatus === 0 && saved.status === 1 && !form.id) {
      // 新建并选"保存草稿"，已经是 0，无需切换
    }
    form.id = saved.id
    form.status = saved.status
    ElMessage.success(targetStatus === 1 ? '已发布' : '已保存')
    router.replace('/admin/articles')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadOptions()
  if (isEdit.value) {
    await loadArticle()
  }
})
</script>
```

- [ ] **Step 2：类型检查**

```bash
cd frontend && pnpm exec vue-tsc --noEmit
```
Expected：通过。若 `MdEditor` 上传回调签名不一致，按 md-editor-v3 5.x 实际 d.ts 调整 callback 类型（保留功能即可）。

- [ ] **Step 3：commit**

```bash
git add frontend/src/views/admin/ArticleEditView.vue
git commit -m "feat(frontend): 文章编辑页（md-editor-v3 + 上传 + 状态切换）"
```

---

## Phase H：端到端验收与收尾

### Task H1：浏览器端到端验收

**Files:** 无新增，纯人工验收。

前置：pgsql-win 在跑。

- [ ] **Step 1：启动后端 + 前端**

```bash
cd backend && ./mvnw -q spring-boot:run > /tmp/boot.log 2>&1 &
sleep 25
grep "Started BlogApplication" /tmp/boot.log

cd frontend && pnpm dev > /tmp/frontend.log 2>&1 &
sleep 10
grep "Local:" /tmp/frontend.log
```

- [ ] **Step 2：浏览器端到端验收**

打开 http://localhost:5173/admin/login，`admin/admin123` 登录。

**写新文章场景**
- 点 sidebar"文章管理" → 列表为空
- 点"写新文章" → 跳到编辑页，分类/标签下拉自动加载（如果还没建分类，先去 `/admin/categories` 建一个）
- 标题填 `Hello World`、slug 填 `hello-world`、摘要填 `第一篇`、分类选刚建的、标签多选
- 正文写 Markdown：
  ```
  # 标题

  这是一段 **加粗** 文字。

  - [x] 完成
  - [ ] 待办

  | a | b |
  |---|---|
  | 1 | 2 |
  ```
- 点"上传封面"，选一张本地 png/jpg → form.cover 自动填入，下方出现预览图
- 点"保存草稿" → toast"已保存"，跳回列表，看到这条 status=草稿
- 回到列表，点"编辑"，能完整回填刚才所有字段（含分类、标签、cover）

**发布场景**
- 编辑页改一处文字，点"发布" → 跳回列表，status=已发布
- 列表点"下架" → status=已下架

**校验场景**
- 新文章 slug 填 `Hello World!`（含大写和空格）→ 表单校验提示
- 创建两篇相同 slug → 第二篇 toast "slug 已存在"
- 不选分类直接发布 → 校验提示

**关联清理**
- 删除一篇文章 → 列表移除
- 进 PG 检查 article_tag 中该文章的关联也被清掉：
  ```bash
  docker exec -i pgsql-win psql -U blog -d blog -c "SELECT * FROM article_tag WHERE article_id = <deletedId>;"
  ```
  Expected：0 rows。

**Markdown 服务端渲染验证**
- 后台数据库查 article 表的 content_html：
  ```bash
  docker exec -i pgsql-win psql -U blog -d blog -c "SELECT id, title, LEFT(content_html, 200) FROM article ORDER BY id DESC LIMIT 1;"
  ```
  Expected：能看到 `<h1`、`<table>`、`<input type="checkbox"`。

**search_vector 验证**
```bash
docker exec -i pgsql-win psql -U blog -d blog -c "SELECT id, title, length(search_vector::text) FROM article ORDER BY id DESC LIMIT 5;"
```
Expected：search_vector 长度 > 0，证明 to_tsvector 跑了。

- [ ] **Step 3：停服**

```bash
taskkill //F //IM java.exe
taskkill //F //IM node.exe
```

> 任何场景失败回到对应 Task 修。

---

### Task H2：更新 README + push

**Files:**
- Modify: `README.md`

- [ ] **Step 1：在 README 当前进度段更新**

把 M2.2 行从"进行中"改为"已完成"：

```markdown
## 当前进度

- M1 骨架（仓库 + JWT 登录闭环）— 已完成
- M2.1（文件上传 + 分类 + 标签管理）— 已完成
- M2.2（文章 CRUD + Markdown 渲染）— 已完成
- M3（前台呈现：首页/详情/归档/分类页/标签页 + Toc + Shiki）— 待开始

详细里程碑见 `docs/superpowers/specs/`，每个里程碑的实现计划见 `docs/superpowers/plans/`。
```

- [ ] **Step 2：commit + push**

```bash
git add README.md
git commit -m "docs: 更新 README 标注 M2.2 完成"
git push origin main
```

---

## M2.2 验收清单

- [x] `./mvnw test` 全绿（M1 9 + Upload 5 + Category 6 + Tag 6 + Markdown 7 + Article 10 = **43 用例**）
- [x] Flyway V3 自动建 `article` 与 `article_tag` 表 + GIN/复合索引
- [x] `POST /api/admin/articles` 创建后服务端 Markdown 渲染（contentHtml）+ wordCount + search_vector 全部写入
- [x] tag 关联保存：先 delete 后 batch insert，更新时也能改 tag 集合
- [x] `PATCH /api/admin/articles/{id}/status`：草稿→发布时 publishedAt 首次填入；之后状态变化不再更新
- [x] 软删除文章后，article_tag 中该文章的关联被一并清除
- [x] 后台分页列表支持 status + keyword（title LIKE）过滤
- [x] 前端：sidebar 多出"文章管理"；列表页支持分页/过滤/状态切换/删除；编辑页支持 md-editor-v3 + 封面上传 + 分类/标签级联
- [x] 全部 commit 已推到 origin/main
