-- D18 回滚脚本：只移除本轮新增的模板版本历史表。
-- 不删除 prompt_template、chat_session、chat_message 或任何用户业务数据。
-- 执行前应确认版本历史不再需要，并按团队备份/审批流程操作。

DROP TABLE IF EXISTS `prompt_template_version`;
