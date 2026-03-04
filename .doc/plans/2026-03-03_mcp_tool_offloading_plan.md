# MCP 工具剥离实施方案（agent-platform-runner）

日期：2026-03-03
范围：将当前运行器中的非核心工具迁移到多个 MCP Server，主服务保留最小核心编排能力。

## 1. 目标

- 将“非核心、可外置”的工具从主服务剥离，降低代码体积与耦合。
- 保持现有 Agent 配置与 SSE 协议尽量不变，优先无感迁移。
- 支持连接多个 MCP Server，并支持动态注册/下线/刷新。

## 2. 当前代码现状（与迁移直接相关）

- 工具元数据已经支持文件热刷新（`tools/*.backend|*.frontend|*.action`）。
- 后端工具执行仍强依赖本地 Java `BaseTool` 实现。
  - 关键点：`ToolExecutionService` 最终调用 `toolRegistry.invoke(...)`。
  - 关键点：`ToolRegistry` 对 backend capability 存在“无 Java 实现则跳过”的逻辑。
- `toolApi` 目前主要用于元数据透传，不参与实际路由。
- 目录热刷新机制已成熟，可复用到 MCP Server 注册目录。

这意味着：
- 当前“配置层动态”已经具备。
- 但要实现“执行层动态（MCP）”，需要新增调用通道而非仅改配置。

## 3. 工具分层建议（保留 vs 外置）

### 3.1 主服务保留（核心）

建议保留以下“编排内核强绑定”工具：
- `_plan_add_tasks_`
- `_plan_update_task_`
- `_plan_get_tasks_`（可见性可配，内部仍可用）

保留原因：
- 与 `ExecutionContext` 的 plan 状态一致性强耦合。
- 保留在进程内可减少跨服务一致性与延迟问题。

### 3.2 第一批外置（低风险，高收益）

迁移到 `mock-mcp-server`：
- `mock_city_weather`
- `mock_logistics_status`
- `mock_ops_runbook`
- `mock_sensitive_data_detector`
- `mock_todo_tasks`
- `mock_transport_schedule`

### 3.3 第二批外置（收益更大，需加强治理）

按安全域拆分 MCP：
- `ops-mcp-server`：`_bash_`
- `skill-mcp-server`：`_skill_run_script_`
- `platform-mcp-server`：`agent_file_create`
- `utility-mcp-server`：`city_datetime`

说明：`_bash_` 与 `_skill_run_script_` 风险最高，建议单独服务并加更强隔离。

## 4. 目标架构

主服务：
- Agent 编排、流式透传、会话记忆、前端 submit 协调、计划状态机。
- 工具执行通过统一 `ToolInvoker` 路由：
  - `LocalToolInvoker`（仅核心内置工具）
  - `McpToolInvoker`（远程 MCP）

MCP 服务群：
- 按领域拆分（mock / ops / skill / platform / utility）。
- 各服务负责自身工具实现、鉴权、审计与限流。

## 5. 核心改造点（代码级）

### 5.1 执行通道抽象（必须）

新增抽象：
- `ToolInvoker` 接口：`invoke(toolName, args, context)`。
- `LocalToolInvoker`：封装现有 `toolRegistry.invoke` 本地执行。
- `McpToolInvoker`：通过 MCP 协议调用远程工具。

改造点：
- `ToolExecutionService` 不再直接依赖本地 `toolRegistry.invoke`，改为按 capability 路由到 invoker。
- 保留 frontend/action 当前语义与 SSE 事件时序不变。

### 5.2 Capability 模型扩展

在 capability 元数据中明确来源：
- `sourceType`: `local | mcp`
- `sourceKey`: 本地可空；MCP 填 serverKey
- `toolApi`: 扩展为 MCP endpoint/route 元信息（可选）

### 5.3 注册中心

新增：
- `McpServerRegistryService`
- `McpCapabilitySyncService`

职责：
- 管理 MCP server 清单。
- 定期/按需拉取每个 server 的工具列表并合并进统一 capability 视图。
- 冲突检测与路由决策（同名工具）。

### 5.4 配置与热更新

新增配置（建议）：
- `agent.mcp.enabled`
- `agent.mcp.servers[]`（静态）
- `agent.mcp.registry.external-dir`（动态文件注册目录，如 `mcp-servers/`）

动态化：
- 复用 `DirectoryWatchService`，监听 `mcp-servers/` 变化。
- 自动触发 server registry reload + capability refresh。

## 6. 多 MCP Server 注册方案

## 6.1 静态注册（生产默认）

在 `application.yml` 声明：
- `serverKey`
- `baseUrl`
- `transport`（http/sse/stdin-stdio 依据实现）
- `auth`（api-key/jwt/mTLS）
- `toolPrefix`（建议强制）
- `timeoutMs` / `retry` / `circuitBreaker`

优点：
- 发布可控、合规简单。

## 6.2 动态注册（运维增强）

新增管理 API（建议）：
- `POST /api/ap/mcp/servers`（注册）
- `DELETE /api/ap/mcp/servers/{serverKey}`（下线）
- `POST /api/ap/mcp/servers/{serverKey}/refresh`（手动刷新）
- `GET /api/ap/mcp/servers`（查看状态）

数据持久化：
- 写入 `mcp-servers/*.json`。
- 与静态配置合并（静态优先或动态优先需明确策略）。

结论：
- 可以动态注册 MCP Server。
- 你当前项目需新增 registry 层与执行路由层后即可支持。

## 7. 工具冲突与命名策略

强烈建议：
- 所有 MCP 工具启用 `toolPrefix`（如 `mock.weather.query`、`ops.bash.run`）。
- 或在注册时提供 alias map。

冲突策略建议：
- 默认拒绝同名冲突并报警。
- 管理端提供显式 override（低频人工操作）。

## 8. 迁移步骤（分阶段实施）

### Phase 0：基线
- 增加日志与指标：按 toolName/sourceType 统计 QPS、延迟、失败率。
- 明确核心工具白名单（plan 三件套）。

### Phase 1：打通 MCP 调用链
- 引入 `McpToolInvoker` 与 server registry。
- 保持现有工具双栈运行（local + mcp），先灰度。

### Phase 2：迁移 mock 工具
- 启动 `mock-mcp-server`。
- 将 mock 工具路由切换至 MCP。
- 删除本地 mock Java 实现与对应资源定义。

### Phase 3：迁移非核心高耦合工具
- 按安全域迁移 `_bash_` / `_skill_run_script_` / `agent_file_create` / `city_datetime`。
- 强化鉴权、沙箱、审计与配额。

### Phase 4：清理与收敛
- 删除不再使用的本地工具代码、配置、测试。
- 更新 agents/resources 示例与文档。

## 9. 代码精简与架构收益（当前仓库测算）

以下为当前仓库行数统计（近似）：

- 可先迁的 mock 工具：
  - 主代码：约 `457` 行 Java
  - 资源定义：约 `336` 行（`tools/` + `src/main/resources/tools/`）
  - 合计（不含测试）：约 `793` 行

- 全量“非核心工具”迁移：
  - 主代码：约 `3367` 行 Java
  - 资源定义：约 `910` 行
  - 合计（不含测试）：约 `4277` 行

- 对应测试可减少：约 `500~800` 行（取决于重写为 MCP 集成测试的比例）。

- 引入 MCP 基础设施新增：预计 `1000~1600` 行（含测试）。

净收益预估：
- 全量迁移后净减少约 `3200~4000` 行。
- 架构上从“单体工具实现耦合”转为“编排内核 + 能力外置服务化”，后续扩展工具成本显著下降。

## 10. 风险与控制

高风险点：
- MCP 不可用导致工具调用失败。
- 网络延迟导致 REACT/PLAN_EXECUTE 回合时延上升。
- `_bash_`、`_skill_run_script_` 外置后安全边界变化。

控制措施：
- 每个 MCP server 配置超时、重试、熔断。
- 按 tool 维度支持降级（返回结构化错误，不打断 run 主链路）。
- 强制鉴权与审计日志（调用方、参数摘要、结果摘要、耗时、错误码）。

## 11. 回滚策略

- 保留本地核心与可选本地非核心实现一段灰度窗口。
- feature flag：`agent.mcp.enabled=false` 可一键回切本地路径。
- 迁移阶段按工具组逐步切换，不做一次性全量切换。

## 12. 建议的立即行动清单

1. 先实现 `ToolInvoker` 抽象和 `McpServerRegistryService`（不迁工具，只打通链路）。
2. 建立 `mock-mcp-server`，先迁 6 个 mock 工具。
3. 补管理端 API，完成 MCP server 动态注册能力。
4. 灰度稳定后再迁 `_bash_` 与 `_skill_run_script_`。

## 13. Phase1 执行门禁（approved）

- 状态：`approved`
- 确认时间：`2026-03-04`
- 执行范围：`Phase1 only`

### 13.1 target_task_ids

- `T0`：补齐计划确认区与执行门禁。
- `T1`：新增 `agent.mcp.*` 配置模型与示例配置。
- `T2`：实现 MCP server registry（静态+文件热刷新）。
- `T3`：实现 Streamable HTTP JSON-RPC 客户端（initialize/tools/list/tools/call，含 SSE 响应解析）。
- `T4`：实现 MCP capability 同步与 ToolRegistry 双源合并（本地优先、alias 注入）。
- `T5`：ToolExecutionService 接入 ToolInvoker 路由。
- `T6`：MCP 失败统一结构化错误返回。
- `T7`：Runner 侧测试补齐并通过。
- `M1`：初始化 `mcp-server-mock` Spring Boot WebFlux 工程。
- `M2`：实现 6 个 mock 工具（新命名）且输出字段与现有本地 mock 完全一致。
- `M3`：Runner↔Mock 联调测试通过。

### 13.2 git_change_scope

- Runner:
  - `src/main/java/com/linlay/agentplatform/{agent,tool,service,config}/**`
  - `src/main/resources/application.yml`
  - `application.example.yml`
  - `src/test/java/com/linlay/agentplatform/{agent,tool,service,controller,config}/**`
  - `.doc/plans/2026-03-03_mcp_tool_offloading_plan.md`
- Mock:
  - `/Users/linlay/Project/mcp-server-mock/**`

### 13.3 acceptance_criteria

1. 默认 `agent.mcp.enabled=false` 时，现有行为无回归（核心测试通过）。
2. 启用 MCP 并配置 server 后，`/api/ap/tools` 返回 MCP 工具且包含 `meta.sourceType/sourceKey`。
3. 同名冲突场景本地优先，MCP 同名只告警不生效。
4. MCP 调用失败返回结构化错误 `{tool,ok:false,code,error}`，运行主链路不中断。
5. 变更 `mcp-servers/*.json` 后可触发 registry + capability 自动刷新。
6. Runner↔Mock 在 SSE/JSON-RPC 下完成 `initialize -> tools/list -> tools/call` 联调。

### 13.4 rollback_rule

- 将 `agent.mcp.enabled` 置为 `false`，即时回切本地工具执行路径。
- 保留本地工具实现，不做删除性改动。

### 13.5 verification_commands

- Runner:
  - `mvn -q -DskipTests compile`
  - `mvn -q test -Dtest=ToolExecutionServiceTest,ToolRegistryTest,DirectoryWatchServiceTest,AgentControllerTest`
- Mock:
  - `mvn -q -DskipTests compile`
  - `mvn -q test`
