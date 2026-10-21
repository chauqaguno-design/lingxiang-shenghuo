-- 只在新建空数据库时导入原始 SQL，不改原文件。
-- 原 SQL 含零日期默认值，仅本次导入会话允许它们。
SET SESSION sql_mode = REPLACE(REPLACE(@@SESSION.sql_mode, 'NO_ZERO_DATE', ''), 'NO_ZERO_IN_DATE', '');
SOURCE /opt/lingxiangshenghuo.sql;
