-- D43 P5 principal 夹具播种（token_sha256=fixture-truth 冻结值；明文不落 DB，H9）
-- 幂等：REPLACE INTO（主键 token_sha256）
REPLACE INTO d43_auth_principal (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled, expires_at) VALUES
('28a253e2581923cc745a0c38cb44f3689800ae920ade3682206a49ace81ebd21','e2e-admin-a','T-A','D-A1','["dev-a1"]','ADMIN',1,NULL),
('ea62eb9d70674701ed5f1e12e04b1a6f42a1677b2664cb31081c6da523931500','e2e-user-a','T-A','D-A1','["dev-a1"]','USER',1,NULL),
('991be0e86eb08f7eb6d6078e5b417087337cb2fe0b0eb71c2a37c0f4d88943bb','e2e-user-a2','T-A','D-A2','[]','USER',1,NULL),
('eda810e5e0c1891dc2535851aab4e91a00fae17d7f3c51ba6331b29f85e312ac','e2e-user-b','T-B','D-B1','["dev-b1"]','USER',1,NULL),
('7effed19b62a60cf3f7f9cf964685b1b26fa6513e242f6911cdeee9e35bb761b','e2e-disabled-a','T-A','D-A1','[]','USER',0,NULL),
('d40309cca33a27add2d35548f5ce723867400e1e6aa10be643d74db6f6797b80','e2e-expired-a','T-A','D-A1','[]','USER',1,'2020-01-01 00:00:00');