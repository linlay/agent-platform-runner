# 日志可观测改造（Controller 全请求 + logging 统一配置 + Tool/Action/Viewport/SSE 开关）

## 1. 背景与目标
- 当前 API 请求入口缺少统一摘要日志，401/403 原因不清晰，排障成本高。
- 本次改造目标：
  - 补齐 `/api/ap/**` 请求摘要日志；
  - 统一日志开关到 `logging.agent.*`；
  - 支持 tool/action/viewport/SSE 事件日志开关；
  - 避免 token/apiKey/authorization 明文出现在日志。

## 2. 范围（In/Out）
### In
- `controller/*`、`security/*`、`service/AgentQueryService`、`agent/runtime/ToolExecutionService`
- `config/*`（新增 logging 配置模型、请求日志过滤器）
- `src/main/resources/application.yml`、`application.example.yml`
- `.doc` 与 README 对齐更新

### Out
- 不变更 API 返回结构（HTTP JSON/SSE 协议）
- 不调整 JWT 校验算法与鉴权策略
- 不调整数据库结构

## 3. 约束与风险
- 约束：默认采用“摘要优先”，SSE 每条事件日志默认关闭，避免日志放大。
- 风险：开启 `tool/action` 的参数/结果日志可能输出业务敏感数据；通过独立开关默认关闭。
- 风险：`logging.agent.*` 为直接迁移，不保留旧 `agent.llm.interaction-log.*` 兼容键。

## 4. 方案与取舍
- 新增 `LoggingAgentProperties`（`logging.agent.*`）统一承载请求、鉴权、异常、tool/action、viewport、sse 开关。
- `LlmInteractionLogProperties` 前缀迁移至 `logging.agent.llm.interaction`。
- 新增 `ApiRequestLoggingWebFilter` 记录请求摘要，不记录 header，query 中敏感键自动掩码。
- `ApiJwtAuthWebFilter` 增加拒绝原因码日志：`missing_auth_header` / `bad_bearer_format` / `empty_bearer_token` / `jwt_verify_failed` / `claim_invalid`。
- `JwksJwtVerifier` 增加 `verifyDetailed` 返回原因码，供鉴权日志判因。
- `DataFileController` 对 403 分支记录 `token_invalid` / `scope_denied` / `asset_denied`。
- `ApiExceptionHandler` 对 4xx/5xx 记录结构化异常摘要。
- `ToolExecutionService` 增加 tool/action start/end/failure 摘要，参数/结果按开关输出。
- `AgentController#viewport` 增加命中/未命中日志。
- `AgentQueryService` 增加 SSE 每事件日志（默认关闭），支持 whitelist 与 payload 开关。

## 5. 任务拆解（带任务 ID）
- [x] T1 基线与文档落盘：创建本计划并记录 `[DOC-GAP]`。
- [x] T2 配置模型改造：新增 `LoggingAgentProperties` 并接入配置绑定。
- [x] T3 LLM 日志迁移：`LlmInteractionLogProperties` 迁移至 `logging.agent.llm.interaction.*`。
- [x] T4 API 请求日志：新增 `ApiRequestLoggingWebFilter` 覆盖 `/api/ap/**`。
- [x] T5 401/403 与异常日志：增强 `ApiJwtAuthWebFilter`、`JwksJwtVerifier`、`DataFileController`、`ApiExceptionHandler`。
- [x] T6 Tool/Action/Viewport/SSE 日志：增强 `ToolExecutionService`、`AgentController#viewport`、`AgentQueryService`。
- [x] T7 配置文件注释与归类：更新 `src/main/resources/application.yml`、`application.example.yml`。
- [x] T8 测试与文档：新增配置绑定与原因码测试；更新 README 与 `.doc` 文档。

## 6. 验收标准
- 任意 `/api/ap/*` 请求存在摘要日志（不含 header）。
- 401/403 可直接看到拒绝原因码。
- tool 调用/结果、action、viewport API、SSE 每条事件均有独立开关。
- 开关统一在 `logging.*` 下并附用途注释。
- 日志中不出现 token/apiKey 明文。

## 7. 回滚方案
- 配置回滚：关闭 `logging.agent.sse.*` 与 `tool/action` 细粒度开关。
- 代码回滚：按 `T6 -> T5 -> T4` 顺序回退，保留最小可观测能力。
- 兼容回滚：若迁移影响运行，回退至改造前版本并恢复旧配置文件。

## 8. 确认区
- 状态: approved
- 结论: 用户已明确要求“PLEASE IMPLEMENT THIS PLAN”并执行。
- 确认时间: 2026-03-02

## 执行记录
- 执行状态: completed
- 偏差说明: `mvn test` 全量执行暴露仓库内既有测试环境问题（与本改造无关），已通过定向测试覆盖本次改造范围。

## [DOC-GAP] 记录
1. `logging.agent.*` 直接迁移，不保留旧 key 兼容期（破坏性配置变更）。
2. 全量测试存在与本改造无关的历史问题（`ToolRegistryTest` 的 `NoClassDefFoundError`），本次不在修复范围。
