-- 报价模块早期同库增量脚本，包含报价表和pig菜单；不会在应用启动时自动执行。
-- 当前独立报价库的结构见quotation-system.sql；此文件仍供隔离H2测试读取报价表定义。
-- 新建表及菜单，不改 datasource / Nacos / Redis / gateway，不导入旧系统账号或客户历史。
-- MySQL 8；请先备份业务库。可以重复执行；不会覆盖已有指标、报价和菜单。
-- 报价指标与产品规则版本。family关联同一系列，version表示正式业务版本，revision用于页面并发校验。
-- kind区分风管、设备、安装及产品规则；body保存JSON规则，发布后通过新版本调整。
CREATE TABLE IF NOT EXISTS quote_catalog (
  id varchar(32) PRIMARY KEY, family varchar(32) NOT NULL, kind varchar(32) NOT NULL,
  version int NOT NULL, status varchar(16) NOT NULL, body longtext NOT NULL,
  revision int NOT NULL DEFAULT 1, created_by bigint NOT NULL, created_at datetime NOT NULL,
  published_at datetime NULL, UNIQUE KEY uk_quote_catalog_version(family,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价指标不可变正式版本';
-- 报价单正文与计算结果快照。family/version关联同一报价的各修订版，owner_id限制普通报价员访问。
-- body保留客户清单来源、供货和安装条件；result保存试算或人工回导金额，state区分草稿、定稿与归档。
CREATE TABLE IF NOT EXISTS quote_order (
  id varchar(32) PRIMARY KEY, family varchar(32) NOT NULL, version int NOT NULL,
  owner_id bigint NOT NULL, body longtext NOT NULL, result longtext NULL,
  list_body text NULL, list_result text NULL,
  state varchar(16) NOT NULL, revision int NOT NULL DEFAULT 1,
  created_at datetime NOT NULL, updated_at datetime NOT NULL,
  UNIQUE KEY uk_quote_order_version(family,version), KEY idx_quote_order_owner(owner_id,state),
  KEY idx_quote_order_updated(updated_at,id), KEY idx_quote_order_owner_updated(owner_id,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单及计算快照';
-- 客户清单解析与回导预览记录，owner_id用于上传文件归属校验。
-- parsed保存工作表、来源证据及差异；先预览后确认，避免上传文件直接覆盖当前报价。
CREATE TABLE IF NOT EXISTS quote_import (
  id varchar(32) PRIMARY KEY, owner_id bigint NOT NULL, filename varchar(255) NOT NULL,
  parsed longtext NOT NULL, created_at datetime NOT NULL, KEY idx_quote_import_owner(owner_id),
  KEY idx_quote_import_created(created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户清单解析快照，不保存账号或执行公式';
-- AI整理v2：原资料保留在quote_import一次，租约/进度与逐批结果分别更新。
CREATE TABLE IF NOT EXISTS quote_ai_job (
  id varchar(32) PRIMARY KEY, owner_id bigint NOT NULL, state varchar(16) NOT NULL,
  meta longtext NOT NULL, created_at datetime NOT NULL, updated_at datetime NOT NULL,
  KEY idx_quote_ai_job_owner_state(owner_id,state,created_at,id),
  CONSTRAINT fk_quote_ai_job_import FOREIGN KEY(id) REFERENCES quote_import(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI整理轻量进度与租约';
CREATE TABLE IF NOT EXISTS quote_ai_batch (
  job_id varchar(32) NOT NULL, batch_index int NOT NULL, label text NOT NULL,
  start_row int NULL, end_row int NULL, batch_state varchar(16) NOT NULL,
  batch_error text NULL, output longtext NULL,
  PRIMARY KEY(job_id,batch_index),
  CONSTRAINT fk_quote_ai_batch_job FOREIGN KEY(job_id) REFERENCES quote_ai_job(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI整理逐批结果';
-- 保存已经生成的Excel二进制快照，quote_id关联对应报价版本。
-- kind区分校对版与正式版，模板版本和SHA-256用于追溯；历史下载直接读取content。
CREATE TABLE IF NOT EXISTS quote_export (
  id varchar(32) PRIMARY KEY, quote_id varchar(32) NOT NULL, kind varchar(16) NOT NULL,
  filename varchar(255) NOT NULL, content longblob NOT NULL, template_version varchar(64) NOT NULL,
  template_sha256 varchar(64) NOT NULL, created_at datetime NOT NULL, KEY idx_quote_export_order(quote_id),
  CONSTRAINT fk_quote_export_order FOREIGN KEY(quote_id) REFERENCES quote_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变报价导出文件';
-- 报价业务操作审计，记录实际操作者、动作、目标和detail中的业务快照。
-- 随报价事务提交，支持追溯指标调整、人工核验、补价回导及正式导出。
CREATE TABLE IF NOT EXISTS quote_audit (
  id varchar(32) PRIMARY KEY, actor_id bigint NOT NULL, actor_name varchar(200) NOT NULL,
  action varchar(64) NOT NULL, target varchar(64) NOT NULL, detail longtext NOT NULL,
  created_at datetime NOT NULL, KEY idx_quote_audit_time(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价业务审计';
-- 报价策略与固定锁记录。policy保存正式模板确认及金额精度策略。
-- catalog-lock提供跨服务实例的数据库行锁，串行化指标发布和系列版本号分配。
CREATE TABLE IF NOT EXISTS quote_setting (
  id varchar(32) PRIMARY KEY, body longtext NOT NULL, revision int NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价策略与并发锁';
-- 仅在记录不存在时初始化策略和锁，重复执行保留已有负责人设置。
INSERT INTO quote_setting(id,body,revision)
SELECT 'policy','{"formal_template_confirmed":true,"unit_round":1,"line_round":1}',1
WHERE NOT EXISTS(SELECT 1 FROM quote_setting WHERE id='policy');
INSERT INTO quote_setting(id,body,revision)
SELECT 'catalog-lock','{}',1 WHERE NOT EXISTS(SELECT 1 FROM quote_setting WHERE id='catalog-lock');
-- BEGIN QUOTATION MENU INIT
-- 菜单使用 930000—930007。若已有同号的其他菜单，先更换下列 ID，勿覆盖原菜单。
START TRANSACTION;
INSERT INTO sys_menu(menu_id,name,permission,path,component,parent_id,icon,visible,sort_order,keep_alive,embedded,menu_type,create_by,del_flag)
SELECT 930000,'报价管理',NULL,'/quotation',NULL,-1,'iconfont icon-dingdan','1',2,'0','0','0','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930000);
INSERT INTO sys_menu(menu_id,name,permission,path,component,parent_id,icon,visible,sort_order,keep_alive,embedded,menu_type,create_by,del_flag)
SELECT 930001,'报价工作台',NULL,'/quotation/workbench/index',NULL,930000,'iconfont icon-dingdan','1',1,'0','0','0','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930001);
INSERT INTO sys_menu(menu_id,name,permission,path,component,parent_id,icon,visible,sort_order,keep_alive,embedded,menu_type,create_by,del_flag)
SELECT 930002,'报价记录',NULL,'/quotation/records/index',NULL,930000,'iconfont icon-wendang','1',2,'0','0','0','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930002);
INSERT INTO sys_menu(menu_id,name,permission,path,component,parent_id,icon,visible,sort_order,keep_alive,embedded,menu_type,create_by,del_flag)
SELECT 930003,'指标管理',NULL,'/quotation/catalog/index',NULL,930000,'iconfont icon-caiji','1',3,'0','0','0','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930003);
INSERT INTO sys_menu(menu_id,name,permission,parent_id,visible,sort_order,menu_type,create_by,del_flag)
SELECT 930004,'查看报价','quote_view',930001,'1',1,'1','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930004);
INSERT INTO sys_menu(menu_id,name,permission,parent_id,visible,sort_order,menu_type,create_by,del_flag)
SELECT 930005,'编辑和试算','quote_edit',930001,'1',2,'1','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930005);
INSERT INTO sys_menu(menu_id,name,permission,parent_id,visible,sort_order,menu_type,create_by,del_flag)
SELECT 930006,'导出报价','quote_export',930001,'1',3,'1','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930006);
INSERT INTO sys_menu(menu_id,name,permission,parent_id,visible,sort_order,menu_type,create_by,del_flag)
SELECT 930007,'报价负责人','quote_manage',930003,'1',4,'1','quotation','0'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=930007);
-- 仅给现有 ADMIN 角色授予新增菜单；普通报价员由管理员在角色管理中分配。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 930000 AND 930007 AND m.create_by='quotation'
WHERE r.role_code='ROLE_ADMIN' AND r.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
COMMIT;
