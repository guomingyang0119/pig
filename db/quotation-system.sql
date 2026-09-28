-- 报价模块独立数据库结构（MySQL 8）。
-- 只创建报价业务表，不创建或复制 pig 的账号、角色、菜单及会话数据。

CREATE TABLE IF NOT EXISTS quote_catalog (
  id varchar(32) PRIMARY KEY,
  family varchar(32) NOT NULL,
  kind varchar(32) NOT NULL,
  version int NOT NULL,
  status varchar(16) NOT NULL,
  body longtext NOT NULL,
  revision int NOT NULL DEFAULT 1,
  created_by bigint NOT NULL,
  created_at datetime NOT NULL,
  published_at datetime NULL,
  UNIQUE KEY uk_quote_catalog_version (family, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价指标不可变正式版本';

CREATE TABLE IF NOT EXISTS quote_order (
  id varchar(32) PRIMARY KEY,
  family varchar(32) NOT NULL,
  version int NOT NULL,
  owner_id bigint NOT NULL,
  body longtext NOT NULL,
  result longtext NULL,
  state varchar(16) NOT NULL,
  revision int NOT NULL DEFAULT 1,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  UNIQUE KEY uk_quote_order_version (family, version),
  KEY idx_quote_order_owner (owner_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单及计算快照';

CREATE TABLE IF NOT EXISTS quote_import (
  id varchar(32) PRIMARY KEY,
  owner_id bigint NOT NULL,
  filename varchar(255) NOT NULL,
  parsed longtext NOT NULL,
  created_at datetime NOT NULL,
  KEY idx_quote_import_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户清单解析快照';

CREATE TABLE IF NOT EXISTS quote_export (
  id varchar(32) PRIMARY KEY,
  quote_id varchar(32) NOT NULL,
  kind varchar(16) NOT NULL,
  filename varchar(255) NOT NULL,
  content longblob NOT NULL,
  template_version varchar(64) NOT NULL,
  template_sha256 varchar(64) NOT NULL,
  created_at datetime NOT NULL,
  KEY idx_quote_export_order (quote_id),
  CONSTRAINT fk_quote_export_order FOREIGN KEY (quote_id) REFERENCES quote_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变报价导出文件';

CREATE TABLE IF NOT EXISTS quote_audit (
  id varchar(32) PRIMARY KEY,
  actor_id bigint NOT NULL,
  actor_name varchar(200) NOT NULL,
  action varchar(64) NOT NULL,
  target varchar(64) NOT NULL,
  detail longtext NOT NULL,
  created_at datetime NOT NULL,
  KEY idx_quote_audit_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价业务审计';

CREATE TABLE IF NOT EXISTS quote_setting (
  id varchar(32) PRIMARY KEY,
  body longtext NOT NULL,
  revision int NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价策略与并发锁';

INSERT INTO quote_setting (id, body, revision)
SELECT 'policy', '{"formal_template_confirmed":true,"unit_round":1,"line_round":1}', 1
WHERE NOT EXISTS (SELECT 1 FROM quote_setting WHERE id = 'policy');

INSERT INTO quote_setting (id, body, revision)
SELECT 'catalog-lock', '{}', 1
WHERE NOT EXISTS (SELECT 1 FROM quote_setting WHERE id = 'catalog-lock');
