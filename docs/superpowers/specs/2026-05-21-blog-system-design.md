# 博客系统设计文档（前后端分离）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 创建日期 | 2026-05-21 |
| 状态 | 已通过头脑风暴阶段，待用户复审 |
| 适用场景 | 个人博客（单作者） |
| 远端仓库 | https://github.com/wgp0805/test.git |

---

## 0. 概述

### 0.1 项目目标

构建一个面向个人作者的博客系统，采用前后端完全分离架构。后端使用 Spring Boot 提供 RESTful API，前端使用 Vue 3 + Vite 提供阅读前台与管理后台。

### 0.2 功能范围

- 文章 CRUD + 分类 + 标签
- Markdown 编辑器 + 代码高亮
- 评论系统（访客留言 + 后台审核）
- 全文搜索 + 归档 + 阅读统计

### 0.3 关键技术决策

- **后端**：Spring Boot 3.3 + Java 17 + MyBatis-Plus + PostgreSQL 16
- **前端**：Vue 3.4 + Vite 5 + TypeScript + Element Plus + UnoCSS
- **鉴权**：JWT（无状态 Token）
- **代码组织**：Monorepo（`backend/` + `frontend/`）
- **评论防刷**：Caffeine 本地缓存（30 秒/IP 限 1 条）
- **文件上传**：本地磁盘 `backend/uploads/`，通过静态资源映射对外暴露

---

## 1. 仓库结构与技术栈

### 1.1 目录布局

```
test/
├── backend/                       # Spring Boot 3.3 + Java 17
│   ├── pom.xml
│   ├── src/main/java/com/blog/
│   │   ├── BlogApplication.java
│   │   ├── config/                # Security/CORS/Jwt/Knife4j 配置
│   │   ├── common/                # 统一响应、异常、分页、工具
│   │   ├── security/              # JwtFilter、UserDetailsService、密码编码
│   │   ├── module/
│   │   │   ├── auth/              # 登录、当前用户信息
│   │   │   ├── article/           # 文章 CRUD、归档、统计
│   │   │   ├── category/          # 分类
│   │   │   ├── tag/               # 标签
│   │   │   ├── comment/           # 评论
│   │   │   ├── search/            # 全文检索
│   │   │   └── stat/              # 阅读量、浏览统计
│   │   └── infrastructure/        # MyBatis-Plus 配置、拦截器
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-dev.yml
│   │   ├── application-prod.yml
│   │   └── db/migration/          # Flyway SQL 迁移
│   └── src/test/                  # JUnit 5 + Testcontainers
│
├── frontend/                      # Vue 3.4 + Vite 5 + TS
│   ├── package.json
│   ├── vite.config.ts
│   ├── uno.config.ts
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── router/                # 前台路由 + 后台路由（守卫）
│   │   ├── stores/                # Pinia
│   │   ├── api/                   # axios + 各模块接口
│   │   ├── layouts/               # BlogLayout、AdminLayout
│   │   ├── views/
│   │   │   ├── blog/              # 前台页面
│   │   │   └── admin/             # 后台页面
│   │   ├── components/            # 通用组件
│   │   ├── composables/
│   │   └── styles/
│   └── tests/                     # Vitest
│
├── docs/
│   └── superpowers/specs/         # 本设计文档位置
├── docker-compose.yml             # 本地 PostgreSQL
├── .gitignore
└── README.md
```

### 1.2 技术栈固化

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3、Java 17、MyBatis-Plus 3.5、PostgreSQL 16、Flyway、Spring Security、JJWT 0.12、Knife4j（OpenAPI 3）、Lombok、MapStruct、flexmark-java（Markdown 渲染）、Caffeine（限流） |
| 前端 | Vue 3.4、Vite 5、TypeScript 5、Pinia、Vue Router 4、Element Plus、UnoCSS、Axios、md-editor-v3、Shiki、Vitest、dayjs |
| 工具 | Docker Compose（本地 PG）、Maven、pnpm |

---

## 2. 数据模型（PostgreSQL Schema）

使用 Flyway 管理迁移。所有表带 `created_at` / `updated_at`，软删除采用 `deleted` 布尔字段（MyBatis-Plus `@TableLogic` 自动过滤）。

### 2.1 表结构

```sql
-- 1. 作者
CREATE TABLE "user" (
  id         BIGSERIAL PRIMARY KEY,
  username   VARCHAR(50)  UNIQUE NOT NULL,
  password   VARCHAR(100) NOT NULL,           -- BCrypt
  nickname   VARCHAR(50)  NOT NULL,
  avatar     VARCHAR(255),
  email      VARCHAR(100),
  bio        VARCHAR(500),
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

-- 2. 分类
CREATE TABLE category (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(50) UNIQUE NOT NULL,
  slug        VARCHAR(50) UNIQUE NOT NULL,
  description VARCHAR(255),
  sort        INT DEFAULT 0,
  deleted     BOOLEAN DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- 3. 标签
CREATE TABLE tag (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(30) UNIQUE NOT NULL,
  slug       VARCHAR(30) UNIQUE NOT NULL,
  deleted    BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- 4. 文章
CREATE TABLE article (
  id            BIGSERIAL PRIMARY KEY,
  title         VARCHAR(200) NOT NULL,
  slug          VARCHAR(200) UNIQUE NOT NULL,
  summary       VARCHAR(500),
  content_md    TEXT NOT NULL,
  content_html  TEXT NOT NULL,
  cover         VARCHAR(255),
  category_id   BIGINT REFERENCES category(id),
  author_id     BIGINT REFERENCES "user"(id),
  status        SMALLINT DEFAULT 0,           -- 0草稿 1发布 2下架
  view_count    BIGINT DEFAULT 0,
  word_count    INT DEFAULT 0,
  published_at  TIMESTAMPTZ,
  search_vector TSVECTOR,
  deleted       BOOLEAN DEFAULT FALSE,
  created_at    TIMESTAMPTZ DEFAULT now(),
  updated_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_article_search ON article USING GIN(search_vector);
CREATE INDEX idx_article_status_published ON article(status, published_at DESC);

-- 5. 文章-标签 多对多
CREATE TABLE article_tag (
  article_id BIGINT REFERENCES article(id) ON DELETE CASCADE,
  tag_id     BIGINT REFERENCES tag(id) ON DELETE CASCADE,
  PRIMARY KEY (article_id, tag_id)
);

-- 6. 评论
CREATE TABLE comment (
  id         BIGSERIAL PRIMARY KEY,
  article_id BIGINT REFERENCES article(id) ON DELETE CASCADE,
  parent_id  BIGINT REFERENCES comment(id),
  nickname   VARCHAR(50)  NOT NULL,
  email      VARCHAR(100),
  website    VARCHAR(255),
  content    VARCHAR(1000) NOT NULL,
  ip         VARCHAR(50),
  user_agent VARCHAR(255),
  status     SMALLINT DEFAULT 0,             -- 0待审 1通过 2拒绝
  deleted    BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_comment_article ON comment(article_id, status);

-- 7. 阅读日志（防刷）
CREATE TABLE article_view_log (
  id         BIGSERIAL PRIMARY KEY,
  article_id BIGINT NOT NULL,
  ip_hash    VARCHAR(64) NOT NULL,
  view_date  DATE NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE (article_id, ip_hash, view_date)
);
```

### 2.2 关键设计点

1. **全文检索**：`article.search_vector` 是 `tsvector` 类型，写入文章时用 `to_tsvector('simple', title || content_md)` 生成。后续如需中文分词，可换 `zhparser`，schema 不变。
2. **Markdown 双存**：`content_md` 用于编辑回填，`content_html` 用于前端展示（避免重复渲染）。
3. **阅读量防刷**：`article_view_log` 用唯一约束 + `ON CONFLICT DO NOTHING`，同一 IP 同一天对同一文章只计一次。
4. **slug 字段**：分类、标签、文章都有 slug，URL 形如 `/article/hello-world`，SEO 友好。
5. **软删除**：评论物理删除，其它表软删除。

### 2.3 初始化数据（V2__seed.sql）

- 默认作者 `admin / admin123`（BCrypt 加密入库）
- 示例分类：`随笔`、`技术`、`生活`
- 示例标签：`Java`、`Vue`、`PostgreSQL`

---

## 3. REST API 接口契约

### 3.1 全局约定

- **Base URL**：`/api`（Vite 代理 `/api → http://localhost:8080`）
- **响应包装**：
  ```json
  { "code": 0, "message": "ok", "data": { ... } }
  ```
- **分页响应**：`{ records, total, page, size }`
- **鉴权**：后台接口携带 `Authorization: Bearer <jwt>`
- **OpenAPI**：`/doc.html`（Knife4j），生产关闭

### 3.2 接口清单

#### 鉴权 `/api/auth`

| Method | Path | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/auth/login` | 否 | `{username, password}` → `{token, expiresIn, user}` |
| GET  | `/auth/me` | 是 | 当前登录用户信息 |
| POST | `/auth/logout` | 是 | 前端清除 token（后端无状态，不维护黑名单） |

#### 文章前台 `/api/articles`

| Method | Path | 说明 |
|---|---|---|
| GET | `/articles?page&size&categorySlug&tagSlug&keyword` | 分页（仅 status=1） |
| GET | `/articles/{slug}` | 详情 + 触发阅读量+1 |
| GET | `/articles/archive` | 按年月聚合 |
| GET | `/articles/hot?limit=10` | 热门 Top N |
| GET | `/articles/{slug}/prev-next` | 上一篇/下一篇 |

#### 文章后台 `/api/admin/articles`

| Method | Path | 说明 |
|---|---|---|
| GET | `/admin/articles?page&size&status&keyword` | 全部（含草稿） |
| POST | `/admin/articles` | 创建 |
| PUT | `/admin/articles/{id}` | 更新 |
| DELETE | `/admin/articles/{id}` | 软删除 |
| PATCH | `/admin/articles/{id}/status` | 切状态 |
| GET | `/admin/articles/{id}` | 编辑回填 |

#### 分类 / 标签

| Method | Path | 说明 |
|---|---|---|
| GET | `/categories` | 公开：所有分类 + 文章计数 |
| GET/POST/PUT/DELETE | `/admin/categories/...` | 后台 CRUD |
| GET | `/tags` | 公开：标签云（含使用次数） |
| GET/POST/PUT/DELETE | `/admin/tags/...` | 后台 CRUD |

#### 评论 `/api/comments`

| Method | Path | 说明 |
|---|---|---|
| GET | `/comments?articleId` | 已通过的评论（含二级回复树） |
| POST | `/comments` | 访客发表（Caffeine 限流） |
| GET | `/admin/comments?page&size&status` | 后台审核列表 |
| PATCH | `/admin/comments/{id}/status` | 通过/拒绝 |
| DELETE | `/admin/comments/{id}` | 物理删除 |

#### 搜索 `/api/search`

| Method | Path | 说明 |
|---|---|---|
| GET | `/search?q=keyword&page&size` | tsvector + ts_headline 高亮 |

#### 统计 `/api/admin/stats`

| Method | Path | 说明 |
|---|---|---|
| GET | `/admin/stats/overview` | 总文章/总评论/今日浏览/累计浏览 |
| GET | `/admin/stats/views?days=30` | 近 N 天浏览趋势 |

#### 文件上传 `/api/admin/upload`

| Method | Path | 说明 |
|---|---|---|
| POST | `/admin/upload` | `multipart/form-data` `file`，返回 `{ url, size, mime }` |

> 限制：单文件 ≤ 5MB，仅 `image/jpeg|png|webp|gif`，扩展名 + MIME 双重校验。

### 3.3 响应示例

**文章详情**：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1,
    "title": "Hello World",
    "slug": "hello-world",
    "summary": "...",
    "contentHtml": "<h1>...</h1>",
    "cover": "/uploads/2026/05/cover.png",
    "category": { "id": 2, "name": "技术", "slug": "tech" },
    "tags": [{ "id": 1, "name": "Java", "slug": "java" }],
    "viewCount": 1234,
    "wordCount": 800,
    "publishedAt": "2026-05-21T10:00:00Z",
    "commentCount": 8
  }
}
```

**错误响应**：
```json
{ "code": 1001, "message": "用户名或密码错误", "data": null }
{ "code": 1404, "message": "文章不存在", "data": null }
{ "code": 9500, "message": "系统繁忙，请稍后重试", "data": null }
```

---

## 4. 后端架构与关键模块

### 4.1 分层

```
Controller   ──► 入参校验、返回 Result<T>
   │
Service      ──► 业务编排、@Transactional 事务、缓存
   │
Mapper       ──► MyBatis-Plus BaseMapper + 自定义 SQL
   │
Entity       ──► 与表 1:1，带 @TableLogic
```

- **Req DTO**：带 Jakarta Validation 注解
- **VO**：列表 VO 与详情 VO 字段区分
- **转换**：MapStruct 编译期生成

### 4.2 模块依赖

```
auth ──► user
article ──► category, tag, comment(只读统计)
search ──► article(只读)
stat ──► article, article_view_log
comment ──► article(只读)
```

模块间只通过 Service 接口调用，不跨模块访问 Mapper。

### 4.3 关键技术点

**JWT 鉴权链路**：
- `JwtAuthenticationFilter`（OncePerRequestFilter）解析 Header、校验签名 + 过期、注入 SecurityContext
- `/api/auth/login` permitAll；`/api/admin/**` authenticated；其余 permitAll
- Token 载荷：`{ uid, username, exp }`，HS256
- 有效期 2h，本期不做 refresh token（到期前端跳登录页）
- 异常入口：`AuthenticationEntryPoint` 返回 `Result(1401)`

**Markdown 渲染管线**（Service 层保存时）：
```
contentMd ──► flexmark-java(GFM + 表格 + 任务列表 + 锚点) ──► contentHtml
          ──► 去 HTML 取文本 ──► word_count
          ──► PG to_tsvector ──► search_vector
```
前端编辑器预览仅供参考，**以服务端渲染为准**。代码高亮在前端用 Shiki 二次处理。

**全文搜索 SQL**：
```sql
SELECT id, title, slug,
       ts_headline('simple', content_md, query, 'MaxWords=30') AS snippet
FROM article, plainto_tsquery('simple', #{q}) query
WHERE search_vector @@ query AND status=1 AND deleted=false
ORDER BY ts_rank(search_vector, query) DESC
LIMIT ... OFFSET ...
```

**阅读量统计**（`@Async` 异步）：
1. `INSERT INTO article_view_log(...) ON CONFLICT DO NOTHING`
2. 若插入成功 → `UPDATE article SET view_count = view_count + 1`
3. 不阻塞详情接口响应

**评论防刷（Caffeine）**：
```java
Cache<String, Long> ipRateLimit = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .maximumSize(10_000)
    .build();
```

**全局异常**（`@RestControllerAdvice`）：
- `BusinessException` → 业务码 + HTTP 200
- `MethodArgumentNotValidException` → 1400
- `AuthenticationException` → 1401
- `AccessDeniedException` → 1403
- 其它 `Exception` → 9500 + 日志 ERROR + traceId

**CORS**：开发放开 `http://localhost:5173`；生产同源部署。

**文件上传安全**：
- MIME + 扩展名白名单
- UUID 重命名，避免路径穿越
- 落盘 `${blog.upload.dir}`（默认 `./uploads`），`/uploads/**` 静态资源映射

**配置文件**：
- `application-dev.yml`：本地 PG、Knife4j 开启、SQL 打印
- `application-prod.yml`：环境变量注入 `${DB_URL}` `${JWT_SECRET}` 等

---

## 5. 前端架构与页面/路由

### 5.1 路由表

**前台（无需登录）**

| Path | 组件 | 说明 |
|---|---|---|
| `/` | `HomeView` | 首页 + 分页 |
| `/article/:slug` | `ArticleDetailView` | 详情 + 评论 |
| `/archive` | `ArchiveView` | 归档时间线 |
| `/category/:slug` | `CategoryView` | 分类下文章 |
| `/tag/:slug` | `TagView` | 标签下文章 |
| `/search?q=` | `SearchView` | 搜索结果 + 高亮 |
| `/about` | `AboutView` | 关于页 |

**后台（`meta.requiresAuth: true`）**

| Path | 组件 | 说明 |
|---|---|---|
| `/admin/login` | `LoginView` | |
| `/admin/dashboard` | `DashboardView` | 概览 + 折线图 |
| `/admin/articles` | `ArticleListView` | 文章管理 |
| `/admin/articles/new` | `ArticleEditView` | 新建 |
| `/admin/articles/:id/edit` | `ArticleEditView` | 编辑 |
| `/admin/categories` | `CategoryView` | |
| `/admin/tags` | `TagView` | |
| `/admin/comments` | `CommentView` | 评论审核 |

### 5.2 路由守卫

```ts
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.token) {
    return { path: '/admin/login', query: { redirect: to.fullPath } }
  }
})
```

### 5.3 Axios 拦截器

- 请求：自动塞 `Authorization: Bearer <token>`
- 响应：
  - `code === 0` → 返回 `data.data`
  - `code === 1401` → 清 token、跳登录、提示
  - 其它非零 → `ElMessage.error(message)` 并 reject
  - HTTP 5xx → `ElMessage.error('系统繁忙')`

### 5.4 状态管理（Pinia）

- **userStore**：token / user / login / logout / fetchMe；token 持久化到 `localStorage`（key=`blog_token`）
- **uiStore**：sidebarCollapsed / theme（'light' | 'dark'），持久化
- **articleStore**：仅缓存"标签云""分类树"，5 分钟过期；不缓存文章详情

### 5.5 Markdown 与代码高亮

- **后台编辑**：`md-editor-v3`，左写右预览，工具栏含图片上传（调 `/api/admin/upload`，回插 `![](url)`）
- **前台展示**：渲染后端给的 `contentHtml`；挂载后 Shiki 替换 `<pre><code>`，懒加载语言包
- **目录 Toc**：扫描 `h1/h2/h3`，生成右侧浮动目录

### 5.6 主题与样式

- 主色：`#3b82f6`
- 字体：正文 `system-ui, "PingFang SC", "Microsoft YaHei"`；代码 `"JetBrains Mono", Consolas, monospace`
- 暗色模式：`html[data-theme="dark"]` 切换 CSS 变量，Element Plus 暗色主题
- UnoCSS 预设：`presetUno + presetAttributify + presetIcons + presetTypography`

### 5.7 Vite 配置要点

```ts
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api':     { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'editor': ['md-editor-v3'],
          'shiki': ['shiki'],
        },
      },
    },
  },
})
```

### 5.8 响应式

- 移动端 `< 768px`：前台 Layout 顶栏折叠为汉堡菜单；后台不刻意优化移动端
- 文章详情页：移动端隐藏右侧 Toc

---

## 6. 错误处理、测试策略、构建与运行

### 6.1 业务码约定

| 区段 | 含义 | 举例 |
|---|---|---|
| `0` | 成功 | |
| `1400-1499` | 参数/校验 | `1400` 参数错误、`1404` 资源不存在 |
| `1401` | 未登录/token 失效 | |
| `1403` | 无权限 | |
| `1429` | 限流 | |
| `1xxx` 其它 | 业务错误 | `1001` 用户名密码错、`1010` slug 重复 |
| `9500` | 系统错误 | 异常兜底 |

### 6.2 异常类层级

```
BusinessException(code, message)
   ├── AuthException        ── 1401/1403
   ├── NotFoundException    ── 1404
   └── RateLimitException   ── 1429
```

所有错误响应 HTTP 200 + 业务码，前端拦截器只关心 `code`。

### 6.3 前端错误展示

- `1401` → 清 token + 跳登录
- `1429` → `ElMessage.warning('操作过于频繁')`
- `9500` / 网络错误 → `ElNotification.error('系统繁忙')`
- 其它业务码 → `ElMessage.error(message)`

### 6.4 日志

- Logback 输出控制台 + 文件（`logs/blog-YYYY-MM-DD.log`，按天滚动，保留 14 天）
- 每个请求注入 `traceId`（MDC + 自定义 filter）

### 6.5 测试策略

**后端**：

| 层级 | 工具 | 覆盖范围 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito | Service 业务逻辑 |
| 集成测试 | Spring Boot Test + Testcontainers（PG 16）| Mapper + 全文检索 + Flyway |
| Web 层 | `@WebMvcTest` + MockMvc | Controller |

必有用例：
- 登录成功/失败/token 过期
- 文章发布后可被搜索（含中英文混合）
- 同 IP 同日访问只 +1 阅读量
- 评论 30s 内连发被限流
- 上传非图片被拒
- 软删除文章不在列表中

**前端（Vitest）**：
- `utils/` 纯函数
- `composables/` 状态切换
- `stores/` 登录登出 + token 持久化
- 关键组件表单校验

**目标覆盖率**：后端 Service 层 ≥ 70%，前端关键 utils/store 必测。

### 6.6 Docker Compose

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: blog
      POSTGRES_USER: blog
      POSTGRES_PASSWORD: blog123
    ports: ["5432:5432"]
    volumes: ["./.data/pg:/var/lib/postgresql/data"]
```

### 6.7 开发命令

```bash
# 启动数据库
docker compose up -d postgres

# 后端
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# → http://localhost:8080 ；Knife4j: http://localhost:8080/doc.html

# 前端
cd frontend
pnpm install
pnpm dev
# → http://localhost:5173
```

### 6.8 生产打包

| 部分 | 产物 | 部署 |
|---|---|---|
| 后端 | `backend/target/blog-0.0.1.jar` | `java -jar -Dspring.profiles.active=prod blog-0.0.1.jar` |
| 前端 | `frontend/dist/` | Nginx 托管 + 反代 `/api`、`/uploads` 到 8080 |
| 数据库 | PostgreSQL 16 | Flyway 启动自动迁移 |

### 6.9 .gitignore 关键项

```
# 后端
backend/target/
backend/uploads/
*.iml
.idea/

# 前端
frontend/node_modules/
frontend/dist/

# 数据
.data/
*.log
```

### 6.10 敏感配置

- 开发：明文（`blog123`、JWT 默认 secret）
- 生产：`${ENV_VAR}` 占位 + `.env.example` 示例

---

## 7. 实现里程碑

| 里程碑 | 内容 |
|---|---|
| **M1 骨架** | Monorepo 初始化、PG 起来、Flyway 跑通、JWT 登录闭环、前端 Layout + 路由 + 登录页跳通 |
| **M2 内容核心** | 文章 CRUD + 分类 + 标签 + Markdown 渲染 + 文件上传 |
| **M3 前台呈现** | 首页、详情、归档、分类/标签页、Toc、代码高亮 |
| **M4 互动与发现** | 评论（含审核 + 防刷）、全文搜索、阅读统计、仪表盘图表 |
| **M5 打磨** | 暗色模式、测试补齐、README、部署 demo |

---

## 8. 待办与风险

### 8.1 本期不做

- Refresh Token（access token 到期跳登录页）
- 多用户/RBAC（仅单作者）
- 中文分词（先用 `simple` 分词器，必要时换 `zhparser`）
- 评论验证码（仅保留 `captcha` 字段口子）
- 文章版本历史
- RSS / Sitemap（M5 可选）
- 后台移动端适配

### 8.2 风险点

| 风险 | 缓解 |
|---|---|
| `simple` 分词对纯中文支持差 | 留 `zhparser` 升级路径，schema 无需改 |
| Markdown 服务端渲染与编辑器预览不一致 | 以服务端为准，编辑器仅供参考 |
| 文件上传到本地磁盘不利于多实例部署 | 个人博客单实例可接受；后续可换对象存储，接口契约不变 |
| 阅读量 `@Async` 失败丢失 | 失败仅日志告警，不重试（个人博客可接受） |
