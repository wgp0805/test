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
