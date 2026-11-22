-- D39 R01 凭据种子（覆盖 P0 取证的外部 5 行：旧 token 明文不可恢复，前态留痕 probe/kp2-principals-before-overwrite.txt）
DELETE FROM d39_auth_principal WHERE user_id IN ('userA','userB','adminT1','userC','expiredUser');
INSERT INTO d39_auth_principal (token_sha256,user_id,tenant_id,dept_id,device_scopes,role,enabled,expires_at) VALUES ('98adf699733c72d88f0c5ba062dc88cb16b6c973c850d6cdb4376a717e385aa2','userA','T1','D1','["DEV-A"]','USER',1,NULL);
INSERT INTO d39_auth_principal (token_sha256,user_id,tenant_id,dept_id,device_scopes,role,enabled,expires_at) VALUES ('8f2071fa1e35b82291ef2830bf601d07509f439aad012b6b0fa703e55555d74b','userB','T1','D2','["DEV-B"]','USER',1,NULL);
INSERT INTO d39_auth_principal (token_sha256,user_id,tenant_id,dept_id,device_scopes,role,enabled,expires_at) VALUES ('039f50a1f8f928d5f8304e6fe27fce444ed7036d983753bf3d0760270688f507','adminT1','T1','D-HQ','[]','ADMIN',1,NULL);
INSERT INTO d39_auth_principal (token_sha256,user_id,tenant_id,dept_id,device_scopes,role,enabled,expires_at) VALUES ('43910351ac694a692d96f42ef56c499174801507a9d75660c1c2e95404898a12','userC','T2','D1','["DEV-A"]','USER',1,NULL);
INSERT INTO d39_auth_principal (token_sha256,user_id,tenant_id,dept_id,device_scopes,role,enabled,expires_at) VALUES ('9a9b99dc4eebaec1454afa89bfcfa1708a352c872b539e2a1a64181b5b6d8e7f','expiredUser','T1','D2','["DEV-B"]','USER',1,'2026-11-21 23:59:59');
