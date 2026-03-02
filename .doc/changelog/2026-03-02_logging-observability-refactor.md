# 2026-03-02_logging-observability-refactor

## 变更类型
- refactor
- docs

## 改动摘要
- 新增 `logging.agent.*` 日志配置模型与请求日志过滤器：
  - `src/main/java/com/linlay/agentplatform/config/LoggingAgentProperties.java`
  - `src/main/java/com/linlay/agentplatform/config/ApiRequestLoggingWebFilter.java`
- 新增通用日志脱敏工具：
  - `src/main/java/com/linlay/agentplatform/service/LoggingSanitizer.java`
- 鉴权与异常链路增强：
  - `ApiJwtAuthWebFilter` 增加 401 拒绝原因日志
  - `JwksJwtVerifier` 增加 `verifyDetailed` 原因码
  - `DataFileController` 增加 403 原因日志
  - `ApiExceptionHandler` 增加结构化异常日志
- Tool/Action/Viewport/SSE 可观测增强：
  - `ToolExecutionService` 增加 start/end/failure 日志与参数/结果开关
  - `AgentController#viewport` 增加命中/未命中日志
  - `AgentQueryService` 增加 SSE 每事件日志（支持 payload 与白名单）
- 配置迁移：
  - `LlmInteractionLogProperties` 前缀迁移为 `logging.agent.llm.interaction`
  - 更新 `src/main/resources/application.yml` 和 `application.example.yml` 注释与开关分组
- 测试与文档：
  - 新增 `LoggingAgentPropertiesBindingTest`
  - 新增 `JwksJwtVerifierReasonCodeTests`
  - 更新 `AgentQueryServiceTest` 的 `ChatSummary` 构造参数
  - 更新 README 与 `.doc/architecture/TECH_STACK.md`

## 计划关联
- plan: `.doc/plans/2026-03-02_logging-observability-refactor.md`
- tasks: T1, T2, T3, T4, T5, T6, T7, T8

## 影响评估
- 配置影响：`agent.llm.interaction-log.*` -> `logging.agent.llm.interaction.*`（直接迁移，旧 key 不兼容）。
- 运行影响：默认新增 API/tool/action/viewport 摘要日志；SSE 每条事件默认关闭。
- 安全影响：新增日志链路统一避免 token/apiKey/authorization 明文输出，且请求日志不记录 header。

## 验证结果
- 编译通过：`mvn -q -DskipTests compile`
- 定向测试通过：
  - `mvn -q -Dtest=LoggingAgentPropertiesBindingTest,JwksJwtVerifierReasonCodeTests,AgentQueryServiceTest,ApiJwtAuthWebFilterTests,DataFileControllerTest test`
  - `mvn -q -Dtest=ToolExecutionServiceTest,DefinitionDrivenAgentTest,AgentRegistryTest test`
- 全量测试现状：
  - `mvn -q test` 失败，错误集中在仓库既有 `ToolRegistryTest`（`NoClassDefFoundError`），不属于本次改造引入问题。

## [DOC-GAP] 决策记录
- `[DOC-GAP]` 采用直接迁移配置键，不保留旧键兼容层。
- `[DOC-GAP]` 既有全量测试失败项未纳入本次修复范围，后续单独治理。
