# blog

个人博客（Spring Boot 3.3 + Vue 3 + PostgreSQL 16），单作者，前后端分离。

## 当前进度

M1 骨架（仓库初始化 + JWT 登录闭环）已完成。后续里程碑见 `docs/superpowers/specs/`。

## 本地启动

```bash
# 1. 启动 PostgreSQL（首次运行）
docker compose up -d postgres
# 已有本地 PG 容器时可复用，在容器内执行：
#   CREATE USER blog WITH PASSWORD 'blog123';
#   CREATE DATABASE blog OWNER blog;

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
