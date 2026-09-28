-- 报价模块增量脚本；使用 pig 自己的业务数据库。不会在应用启动时自动执行。
-- 新建表及菜单，不改 datasource / Nacos / Redis / gateway，不导入旧系统账号或客户历史。
-- MySQL 8；请先备份业务库。可以重复执行；不会覆盖已有指标、报价和菜单。
CREATE TABLE IF NOT EXISTS quote_catalog (
  id varchar(32) PRIMARY KEY, family varchar(32) NOT NULL, kind varchar(32) NOT NULL,
  version int NOT NULL, status varchar(16) NOT NULL, body longtext NOT NULL,
  revision int NOT NULL DEFAULT 1, created_by bigint NOT NULL, created_at datetime NOT NULL,
  published_at datetime NULL, UNIQUE KEY uk_quote_catalog_version(family,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价指标不可变正式版本';
CREATE TABLE IF NOT EXISTS quote_order (
  id varchar(32) PRIMARY KEY, family varchar(32) NOT NULL, version int NOT NULL,
  owner_id bigint NOT NULL, body longtext NOT NULL, result longtext NULL,
  state varchar(16) NOT NULL, revision int NOT NULL DEFAULT 1,
  created_at datetime NOT NULL, updated_at datetime NOT NULL,
  UNIQUE KEY uk_quote_order_version(family,version), KEY idx_quote_order_owner(owner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单及计算快照';
CREATE TABLE IF NOT EXISTS quote_import (
  id varchar(32) PRIMARY KEY, owner_id bigint NOT NULL, filename varchar(255) NOT NULL,
  parsed longtext NOT NULL, created_at datetime NOT NULL, KEY idx_quote_import_owner(owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户清单解析快照，不保存账号或执行公式';
CREATE TABLE IF NOT EXISTS quote_export (
  id varchar(32) PRIMARY KEY, quote_id varchar(32) NOT NULL, kind varchar(16) NOT NULL,
  filename varchar(255) NOT NULL, content longblob NOT NULL, template_version varchar(64) NOT NULL,
  template_sha256 varchar(64) NOT NULL, created_at datetime NOT NULL, KEY idx_quote_export_order(quote_id),
  CONSTRAINT fk_quote_export_order FOREIGN KEY(quote_id) REFERENCES quote_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变报价导出文件';
CREATE TABLE IF NOT EXISTS quote_audit (
  id varchar(32) PRIMARY KEY, actor_id bigint NOT NULL, actor_name varchar(200) NOT NULL,
  action varchar(64) NOT NULL, target varchar(64) NOT NULL, detail longtext NOT NULL,
  created_at datetime NOT NULL, KEY idx_quote_audit_time(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价业务审计';
CREATE TABLE IF NOT EXISTS quote_setting (
  id varchar(32) PRIMARY KEY, body longtext NOT NULL, revision int NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价策略与并发锁';
INSERT INTO quote_setting(id,body,revision)
SELECT 'policy','{"formal_template_confirmed":true,"unit_round":1,"line_round":1}',1
WHERE NOT EXISTS(SELECT 1 FROM quote_setting WHERE id='policy');
INSERT INTO quote_setting(id,body,revision)
SELECT 'catalog-lock','{}',1 WHERE NOT EXISTS(SELECT 1 FROM quote_setting WHERE id='catalog-lock');
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
