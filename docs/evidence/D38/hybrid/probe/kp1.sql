SET client_encoding TO 'UTF8';
\echo '== simple 中文连写 =='
SELECT to_tsvector('simple','数控机床主轴过热报警');
\echo '== simple 含编码数字句 =='
SELECT to_tsvector('simple','E5 偏差超过20%时判定温度传感器老化');
\echo '== 是否有 chinese 配置 =='
SELECT count(*) AS chinese_cfg FROM pg_ts_config WHERE cfgname='chinese';
\echo '== zhparser/pg_jieba 可用性 =='
SELECT name, installed_version FROM pg_available_extensions WHERE name IN ('zhparser','pg_jieba','pg_trgm') ORDER BY name;