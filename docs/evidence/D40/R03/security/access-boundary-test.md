# D40 R03-08 生产权限边界与 two_ 兼容路径验证

## 1. 验证对象

- `com.zl.d40.knowledge.sync.SyncAdminBoundaryConfiguration`（enforce-admin 启动门禁）
- `com.zl.d40.access.AccessGuard`（requireAdmin / require / requireDocumentVisible）
- `com.zl.d40.access.AuthContextFilter`（服务端凭据表解析，fail-closed）
- `com.zl.d40.two.TwoPgVectorConfiguration`（two_ 手动装配，setSyncStateRequired(false)）
- `D40_DUDACR/src/main/resources/application.yml`（本轮新增 `app.access.enforce-admin: ${APP_ACCESS_ENFORCE_ADMIN:false}`）

## 2. 测试证据（SyncAssemblyBoundaryTest，6/6 绿）

| 编号 | 断言 | 结果 |
|---|---|---|
| T-R03-31 | 生产主轨 syncStateRequired=true 且 SyncService 缺席 → deleteByDocumentVersion 抛 IllegalStateException，PG 删除从未执行（不静默降级 legacy） | PASS |
| T-R03-32 | 显式 setSyncStateRequired(false) → legacy 删除放行 | PASS |
| T-R03-33 | enforce-admin=true 且 AccessGuard 缺席 → SmartInitializingSingleton 启动抛 IllegalStateException（fail-closed） | PASS |
| T-R03-34 | AccessGuard 在场 → 启动检查通过 | PASS |
| T-R03-35 | 匿名 → AUTH_CONTEXT_MISSING；USER → AUTH_FORBIDDEN；ADMIN → 放行（拒绝不静默） | PASS |
| T-R03-36 | two_ 语义（不注入 syncService + legacy 声明）删除后 d40_document_sync 零行（不污染主轨） | PASS |

原始日志：`test/p2-test-run.log`、`test/full-module-test-run.log`；Surefire：`test/surefire/`。

## 3. 真实 HTTP 权限面佐证（E1–E5 演练）

- 演练实例 `app.access.enabled=true`，AuthContextFilter 全局 fail-closed；
  全部管理端点（sync retry/status/consistency、vector-index 写面）经临时 ADMIN
  principal（随机 token，SHA-256 入库，演练后删除）Bearer 认证调用成功。
- 拒绝通道契约：HTTP 200 + Result.fail(4001, AUTH_*)（沿用 D39 体系，未改异常处理器）。
- 管理重试/一致性端点 requireAdmin + 审计（d40_admin_audit）；演练清理后审计表
  按批次 actor/target 精确删除，PRE/POST 指纹一致（e1-e5/fingerprint-*.json）。

## 4. 配置与装配边界结论

- 生产 Profile 设 `APP_ACCESS_ENABLED=true` + `APP_ACCESS_ENFORCE_ADMIN=true` 时，
  AccessGuard 缺席即启动失败；不存在"权限组件缺席=默认放行"的特权写面。
- two_ 兼容路径：独立配置前缀、two_ 表前缀强制、initializeSchema/removeExisting 恒 false、
  显式 legacy 声明且不写主轨同步状态；不会被误认为生产主轨（主轨默认 fail-fast）。
- 本轮 yml 仅追加 enforce-admin 键（默认 false，本地 mock 演练零差异），未改既有键。

## 5. 未覆盖项（如实记录）

- 未以真实生产 Profile 启动完整 Spring 上下文做端到端启动失败演练
  （enforce-admin 门禁以组件级 SmartInitializingSingleton 调用验证）；
  建议后续在集成测试 profile 中补充 ApplicationContextRunner 启动级断言。
