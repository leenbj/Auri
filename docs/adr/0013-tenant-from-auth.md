---
status: accepted
---
# ADR-0013：Tenant 只由认证结果映射得出，V1 映射到唯一的固定 Tenant

V1 网关只服务一个 User，但之后会增加用户管理系统。因此所有数据与接口从第一天起都带 `tenant_id`，且 `tenant_id` 只能由认证结果映射得到（设备与 Agent 的凭证 → Tenant），代码与数据里不写死任何租户常量；V1 的映射恰好只有一个固定 Tenant。约定一个 User 对应一个 Tenant（私有空间）；多人共享的团队 Tenant 不在本决策范围，需要时再引入。

## Considered Options

- **V1 不带 `tenant_id`，用户系统上线时再迁移**：否决，Delivery 的唯一键、权限范围与隔离测试都围绕 `tenant_id`，事后补是全库迁移。
- **V1 直接做完整多用户**：否决，注册、登录、找回等用户管理与首版目标无关。

## Consequences

- 用户管理系统上线时，只需替换“凭证 → Tenant”的映射并增加注册、登录，数据模型与接口不变。
- 请求正文里自报的 `tenant_id` 一律不被信任（对应 PRD FR-MCP-005、T-042）。
- 本决策只管 Tenant 的来源；设备与 Agent 凭证由谁签发、如何配对，属于 ADR-008，另行决定。
