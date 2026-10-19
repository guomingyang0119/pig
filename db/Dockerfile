FROM registry.cn-hangzhou.aliyuncs.com/dockerhub_mirror/mysql-server:8.0.32

MAINTAINER lengleng(wangiegie@gmail.com)

ENV TZ=Asia/Shanghai
ENV LANG C.UTF-8

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY ./pig.sql /docker-entrypoint-initdb.d

COPY ./pig_config.sql /docker-entrypoint-initdb.d

# 根目录 Compose 从全新 MySQL 数据目录启动时同时创建独立报价库。
COPY ./quotation-system.sql /tmp/quotation-system.sql
RUN printf 'CREATE DATABASE IF NOT EXISTS QuotationSystem CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nUSE QuotationSystem;\n' > /docker-entrypoint-initdb.d/zz_quotation.sql \
	&& cat /tmp/quotation-system.sql >> /docker-entrypoint-initdb.d/zz_quotation.sql

# 菜单定义只保留在 quotation.sql；首装时抽取菜单段到 pig 系统库。
COPY ./quotation.sql /tmp/quotation.sql
RUN sed -n '/^-- BEGIN QUOTATION MENU INIT$/,$p' /tmp/quotation.sql > /tmp/quotation-menu.sql \
	&& test -s /tmp/quotation-menu.sql \
	&& printf 'USE pig;\n' > /docker-entrypoint-initdb.d/zzz_quotation_menu.sql \
	&& cat /tmp/quotation-menu.sql >> /docker-entrypoint-initdb.d/zzz_quotation_menu.sql
