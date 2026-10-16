-- 报价模块独立数据库结构（MySQL 8）。
-- 只创建报价业务表，不创建或复制 pig 的账号、角色、菜单及会话数据。

-- 报价指标与产品规则版本。family关联同一系列，version表示正式业务版本，revision用于页面并发校验。
-- kind区分风管、设备、安装及产品规则；body保存JSON规则，发布后通过新版本调整。
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

-- 报价单正文与计算结果快照。family/version关联同一报价的各修订版，owner_id限制普通报价员访问。
-- body保留客户清单来源、供货和安装条件；result保存试算或人工回导金额，state区分草稿、定稿与归档。
CREATE TABLE IF NOT EXISTS quote_order (
  id varchar(32) PRIMARY KEY,
  family varchar(32) NOT NULL,
  version int NOT NULL,
  owner_id bigint NOT NULL,
  body longtext NOT NULL,
  result longtext NULL,
  list_body text NULL, list_result text NULL,
  state varchar(16) NOT NULL,
  revision int NOT NULL DEFAULT 1,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  UNIQUE KEY uk_quote_order_version (family, version),
  KEY idx_quote_order_owner (owner_id, state),
  KEY idx_quote_order_updated (updated_at, id), KEY idx_quote_order_owner_updated (owner_id, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单及计算快照';

-- 客户清单解析与回导预览记录，owner_id用于上传文件归属校验。
-- parsed保存工作表、来源证据及差异；先预览后确认，避免上传文件直接覆盖当前报价。
CREATE TABLE IF NOT EXISTS quote_import (
  id varchar(32) PRIMARY KEY,
  owner_id bigint NOT NULL,
  filename varchar(255) NOT NULL,
  parsed longtext NOT NULL,
  created_at datetime NOT NULL,
  KEY idx_quote_import_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户清单解析快照';

-- 保存已经生成的Excel二进制快照，quote_id关联对应报价版本。
-- kind区分校对版与正式版，模板版本和SHA-256用于追溯；历史下载直接读取content。
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

-- 报价业务操作审计，记录实际操作者、动作、目标和detail中的业务快照。
-- 随报价事务提交，支持追溯指标调整、人工核验、补价回导及正式导出。
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

-- 报价策略与固定锁记录。policy保存正式模板确认及金额精度策略。
-- catalog-lock提供跨服务实例的数据库行锁，串行化指标发布和系列版本号分配。
CREATE TABLE IF NOT EXISTS quote_setting (
  id varchar(32) PRIMARY KEY,
  body longtext NOT NULL,
  revision int NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价策略与并发锁';

-- 仅在记录不存在时初始化策略和锁，重复执行保留已有负责人设置。
INSERT INTO quote_setting (id, body, revision)
SELECT 'policy', '{"formal_template_confirmed":true,"unit_round":1,"line_round":1}', 1
WHERE NOT EXISTS (SELECT 1 FROM quote_setting WHERE id = 'policy');

INSERT INTO quote_setting (id, body, revision)
SELECT 'catalog-lock', '{}', 1
WHERE NOT EXISTS (SELECT 1 FROM quote_setting WHERE id = 'catalog-lock');
