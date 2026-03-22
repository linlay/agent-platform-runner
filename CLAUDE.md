# CLAUDE.md

## Project Overview

Spring Boot agent gateway — 基于 WebFlux 的响应式 LLM Agent 编排服务，通过 YAML 配置定义 Agent，支持多种执行模式、H2A 双队列流式管道、原生 OpenAI Function Calling 协议，以及定义驱动的工具 / Skill / Schedule / MCP 集成。

**技术栈:** Java 21, Spring Boot 3.3.8, WebFlux (Reactor), Jackson

**LLM 提供商:** Bailian (阿里云百炼/Qwen), SiliconFlow (DeepSeek), Babelark 等；provider 只承载连接配置，实际调用协议由模型定义决定。

## Build & Run

```bash
mvn clean test                          # 构建并运行所有测试
make run                                # 本地启动（自动加载根目录 .env）
mvn test -Dtest=ClassName               # 运行单个测试类
mvn test -Dtest=ClassName#methodName    # 运行单个测试方法
```

流式事件模块源码位于 `src/main/java/com/linlay/agentplatform/stream/**`。

### 发布相关文件放置约定

- `Dockerfile` 与 `settings.xml` 保持在项目根目录，以匹配标准 `docker build .` 上下文和当前脚本路径约定。
- `configs/` 保持在项目根目录，作为结构化配置模板目录。
- `nginx.conf` 当前保持在项目根目录，作为反向代理示例；若后续扩展多环境部署资产，可迁移到 `deploy/nginx/`。
- `.dockerignore` 需要保留，用于缩小构建上下文并避免将本地敏感配置（如 `configs/*.yml` / `configs/**/*.pem`）带入镜像构建上下文。

## Architecture

```text
POST /api/query
  → AgentController
  → AgentQueryService
  → ActiveRunService.register()
  → DefinitionDrivenAgent.stream()
  → AgentMode / OrchestratorServices
  → LlmService.streamDeltas()
  → AgentDelta
  → StreamEventAssembler / StreamSseStreamer
  → RenderQueue
  → SSE response
```

运行中控制面：

```text
POST /api/steer
  → ActiveRunService.steer()
  → RunControl.enqueueSteer()
  → RunInputBroker.pendingSteers
  → OrchestratorServices.injectPendingSteers()
  → request.steer SSE + 对话上下文注入

POST /api/interrupt
  → ActiveRunService.interrupt()
  → RunControl.cancelSink / interrupted flag
  → 清空 steer 队列 + 取消 frontend submit 等待 + 中断 runner 线程
  → run.cancel SSE
```

### H2A 双队列流式管道

H2A 不是“零缓冲口号”，而是一个可控的流式传输层：

- `RunInputBroker` 维护多类运行中输入：
  - `Steer` 队列：缓冲用户在 run 进行中追加的引导消息。
  - `Interrupt` 队列：缓冲取消信号。
  - `submitWaiters / bufferedSubmits`：承接 frontend tool 的人机提交。
- `RunControl` 通过 `AtomicBoolean interrupted` + `Sinks.One<Void> cancelSink` 形成响应式取消链路；interrupt 到达后会：
  - 拒绝新的 steer。
  - 清空待注入 steer。
  - 取消全部 frontend submit 等待。
  - 中断 runner 线程。
- `OrchestratorServices.injectPendingSteers()` 在每次 `callModelTurnStreaming()` 开始前批量排出 steer 队列：
  - 追加到模型上下文。
  - 生成 `request.steer` 事件通知客户端。
- `RenderQueue` 提供可参数化的 H2A 输出缓冲：
  - `flush-interval-ms`
  - `max-buffered-chars`
  - `max-buffered-events`
  - 三者都为 `0` 时退化为直通透传。
  - `run.complete` / `run.cancel` / `run.error` 与心跳会强制 flush，不参与普通缓冲策略。

关键类：`RunInputBroker`、`RunControl`、`RenderQueue`、`H2aProperties`、`ActiveRunService`

### 核心技术能力

#### 六项核心技术

1. **H2A 双队列流式管道** — steer / interrupt / render buffering 一体化控制，既支持近实时输出，也支持有界缓冲与可取消的人机协作。
2. **定义驱动 + 依赖感知热重载** — Agent / Model / Tool / Skill / Provider / Team / Schedule / MCP Server / Viewport 由外部 YAML 驱动，并按依赖精准刷新受影响 agent。
3. **System Prompt 多层合并管线** — `SOUL.md → Runtime Context → AGENTS.md / AGENTS.<stage>.md → memory → YAML prompt → skills/tool appendix` 的分层提示词体系，支持通过 `contextConfig.tags` 按需注入运行时上下文。
4. **Container Hub 三级沙箱生命周期** — `RUN / AGENT / GLOBAL` 三级复用与销毁策略，配合引用计数、空闲驱逐和平台目录挂载策略。
5. **SSE 事件契约** — 围绕 Event Model v2 的统一事件字段、业务语义和历史回放约束。
6. **Chat Memory V3.1** — JSONL 增量落盘、按 run 滑动窗口、`_msgId` 关联多消息拆分、真实 `_usage` 透传、reasoning 不回放。

#### 七项重要技术

7. **三种 Agent 执行模式** — `OneshotMode`、`ReactMode`、`PlanExecuteMode` 三种模式由 `sealed` 层级统一抽象。
8. **Backend / Frontend / Action 多类型工具系统** — 同一 Function Calling 协议下走不同执行路径，支撑后端调用、前端渲染和即时动作。
9. **MCP Server 集成与可用性管理** — 目录式注册、协议版本管理、可用性 gate、自动重连、依赖传播刷新。
10. **JWT + Chat Image Token 双层鉴权** — `/api/*` 走 JWT，`/api/data` 支持 HMAC-SHA256 签名的 chat image token 与密钥轮换。
11. **Budget / Compute Policy 资源限制** — `RunSpec + Budget + ComputePolicy` 在 run / model / tool 三层限制调用量与超时。
12. **Bash 工具安全模型** — 白名单命令、路径白名单、按命令分级路径校验、shell 特性开关、git 参数特化解析。
13. **Schedule / Cron 定时编排** — YAML 定义 + Spring `CronTrigger` + 增量 reconcile，每次触发都走标准 `QueryRequest` 链路。

### 核心模块

| 包 | 职责 |
|---|------|
| `agent` | Agent 接口、`DefinitionDrivenAgent` 主实现、`AgentRegistry`、YAML 定义加载、目录化 Agent prompt 组装 |
| `agent.mode` | `AgentMode`（sealed：`OneshotMode` / `ReactMode` / `PlanExecuteMode`）、`OrchestratorServices` 流式编排、`StageSettings` |
| `agent.runtime` | `ExecutionContext`、`RunControl`、`RunInputBroker`、`ToolExecutionService`、Container Hub 沙箱接入 |
| `agent.runtime.policy` | `RunSpec`、`ToolChoice`、`ComputePolicy`、`Budget` 等策略定义 |
| `stream` | `StreamEventAssembler`、`StreamSseStreamer`、`RenderQueue`、H2A 传输整形 |
| `model` | `AgentRequest`、`ModelProperties`、`ModelDefinition`、`ModelProtocol`、`ViewportType` |
| `model.api` | REST 契约：`ApiResponse`、`QueryRequest`、`SubmitRequest`、`SteerRequest`、`InterruptRequest`、`ChatDetailResponse` 等 |
| `model.stream` | 流式领域模型：`AgentDelta`、`ToolCallDelta`、SSE payload 映射 |
| `service` | `LlmService`、`AgentQueryService`、`ActiveRunService`、`ChatRecordStore`、`DirectoryWatchService`、MCP 同步与重连 |
| `tool` | `BaseTool`、`ToolRegistry`、`ToolFileRegistryService`、内置 `_bash_` / `datetime` / `container_hub_bash` 等 |
| `skill` | `SkillRegistryService`、`SkillDescriptor`、`SkillProperties`、运行时 prompt 注入 |
| `schedule` | Schedule 注册、增量 reconcile、Cron dispatch |
| `security` | `ApiJwtAuthWebFilter`、`ChatImageTokenService`、JWT/JWKS 本地与远程校验 |
| `controller` | REST API：`/api/agents`、`/api/teams`、`/api/skills`、`/api/tools`、`/api/tool`、`/api/chats`、`/api/chat`、`/api/query`、`/api/submit`、`/api/steer`、`/api/interrupt`、`/api/viewport`、`/api/data` |
| `memory` | Chat Memory V3.1 JSONL 增量存储与回放，滑动窗口默认 `k=20` |

### 关键设计

- **定义驱动** — Agent 支持扁平 YAML（`agents/<key>.yml`）与目录化布局（`agents/<key>/agent.yml`），目录化布局可附带 `SOUL.md` / `AGENTS*.md` / `memory/` / per-agent `skills/` / `tools/`。
- **提示词分层管理** — 人格、角色知识、阶段指令、记忆、技能说明、工具说明拆成独立来源，运行时按顺序合并。
- **依赖感知热重载** — 基于 `WatchService + 防抖 + CatalogDiff + AgentDependencyIndex`，支持 `Provider → Model → Agent`、`MCP Tool → Agent`、`Tool → Agent` 级联刷新；`skills` 仅刷新技能注册表。
- **MCP 可用性门控** — `McpServerAvailabilityGate` 记录失败窗口，`McpReconnectOrchestrator` 定时重试 due servers，并只刷新受影响 agent。
- **响应格式统一** — 非 SSE 接口统一 `{"code": 0, "msg": "success", "data": {}}`；`/api/query` 结束时追加 `data:[DONE]` 传输层终止帧。
- **会话详情稳定契约** — `GET /api/chat` 的 `data` 字段固定为 `chatId/chatName/rawMessages/events/references`；`events` 必返，`rawMessages` 仅在 `includeRawMessages=true` 返回。

## Agent Definition 文件格式

### 完整 Schema

```yaml
key: agent_key
name: agent_name
role: 角色标签
description: 描述
icon: "emoji:🤖"
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: true
    effort: MEDIUM
  temperature: 0.7
  top_p: 0.95
  max_tokens: 4096
toolConfig:
  backends: ["_bash_", "datetime"]
  frontends: ["show_weather_card"]
  actions: ["switch_theme"]
skillConfig:
  skills: ["docx", "screenshot"]
contextConfig:
  tags: ["system", "context", "owner", "auth"]
mode: ONESHOT
toolChoice: AUTO
budget:
  runTimeoutMs: 120000
  model:
    maxCalls: 15
    timeoutMs: 60000
    retryCount: 0
  tool:
    maxCalls: 20
    timeoutMs: 120000
    retryCount: 0
sandboxConfig:
  environmentId: shell
  level: agent
plain:
  systemPrompt: 系统提示词
  modelConfig:
    modelKey: bailian-qwen3-max
  toolConfig: null
react:
  systemPrompt: 系统提示词
  maxSteps: 6
planExecute:
  plan:
    systemPrompt: 规划提示词
    deepThinking: true
  execute:
    systemPrompt: 执行提示词
  summary:
    systemPrompt: 总结提示词
  maxSteps: 10
runtimePrompts:
  planExecute:
    taskExecutionPromptTemplate: "..."
  skill:
    catalogHeader: "..."
    disclosureHeader: "..."
    instructionsLabel: "..."
  toolAppendix:
    toolDescriptionTitle: "..."
    afterCallHintTitle: "..."
```

Agent Definition 仅支持 YAML，推荐新建 Agent 使用 `.yml`。前 4 行固定为 `key`、`name`、`role`、`description`，方便渐进式披露：

```yaml
key: agent_key
name: agent_name
role: 角色标签
description: 描述
icon: "emoji:🤖"
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: true
    effort: MEDIUM
toolConfig:
  backends: ["_bash_", "datetime"]
  frontends: ["show_weather_card"]
  actions: ["switch_theme"]
mode: ONESHOT
plain:
  systemPrompt: |
    系统提示词
    支持多行
```

### Agent / Skill / Tool REST 契约

- `GET /api/agents`：返回 `AgentSummary[]`，顶层包含 `key/name/icon/description/role/meta`。
- `GET /api/teams`：返回 `TeamSummaryResponse[]`，字段包含 `teamId/name/icon/agentKeys/meta.invalidAgentKeys/meta.defaultAgentKey/meta.defaultAgentKeyValid`。
- `GET /api/skills?tag=...`：返回 `SkillSummary[]`，字段为 `key/name/description/meta.promptTruncated`。
- `GET /api/tools?tag=...&kind=backend|frontend|action`：返回 `ToolSummary[]`，字段为 `key/name/label/description/meta(kind/toolType/viewportKey/strict/sourceType/sourceKey)`。
- `GET /api/tool?toolName=...`：返回 `ToolDetail`，字段为 `key/name/label/description/afterCallHint/parameters/meta`。
- `GET /api/chats`：返回会话索引摘要，支持按 `agentKey` 与 `lastRunId` 增量查询。
- `GET /api/chat?chatId=...`：返回稳定结构 `chatId/chatName/events/references`；`includeRawMessages=true` 时额外返回 `rawMessages`。
- `POST /api/query`：启动一次 run；默认返回 SSE。
- `POST /api/submit`：提交 frontend tool 的人机参数，请求体固定为 `runId + toolId + params`。
- `POST /api/steer`：运行中追加用户引导，请求体为 `SteerRequest`；当 run 仍处于活跃状态时会写入 steer 队列并在下一个模型回合前注入。
- `POST /api/interrupt`：运行中断，请求体为 `InterruptRequest`；成功后触发 `run.cancel`，并取消待处理 steer / frontend submit。
- `GET /api/viewport?viewportKey=...`：返回 viewport 内容；本地未命中时可回退到 viewport server。
- `GET /api/data?...`：提供静态文件访问，支持 chat image token 校验。
- `/api/tool` 未命中 `toolName`、`kind` 非法时均返回 HTTP `400`（`ApiResponse.failure`）。

### 模式配置块

各模式对应配置块（至少需要一个）：

- `ONESHOT` → `plain.systemPrompt`
- `REACT` → `react.systemPrompt`
- `PLAN_EXECUTE` → `planExecute.plan.systemPrompt` + `planExecute.execute.systemPrompt`

### 配置规则

**modelConfig 继承：**

- 支持外层默认 + stage 内层覆盖；内层优先。
- 外层 `modelConfig` 可省略，但“外层或任一 stage”至少要有一处 `modelConfig.modelKey`。
- `provider/modelId/protocol` 不在 Agent Definition 文件中声明，统一由 `models/<modelKey>.yml` 解析得到。

**toolConfig 继承：**

- 支持外层默认 + stage 覆盖。
- 若 stage 显式 `toolConfig: null` 表示清空该 stage 普通工具集合。
- `PLAN_EXECUTE` 强制工具不受 `toolConfig: null` 影响：plan 固定含 `_plan_add_tasks_`，execute 固定含 `_plan_update_task_`。
- `_plan_get_tasks_` 仅在阶段显式配置时对模型可见；框架内部调度始终可读取 plan 快照。

**skillConfig 配置：**

- 使用 `skillConfig.skills` 声明 skills。

**contextConfig 配置：**

```yaml
contextConfig:
  tags:
    - system
    - context
    - owner
    - auth
    - sandbox
    - all-agents
```

- `system`：注入 OS、Java 版本、时区、locale、当前日期时间等系统环境信息。
- `context`：注入运行目录与当前会话上下文，包括 `runtime_home`、`root_dir`、`agents_dir`、`chats_dir`、`data_dir`、`chatId`、`requestId`、`runId`、`agentKey`、`teamId`、`role`、`chatName`、`scene`、`references`。
- `owner`：注入 `OWNER.md` 中的 frontmatter 与正文摘要（正文最大 4000 字符）。
- `auth`：注入 JWT 主体信息，包括 `subject`、`deviceId`、`scope`、`issuedAt`、`expiresAt`。
- `sandbox`：注入本地 `sandboxConfig` 摘要，并从 `agent-container-hub` 的 `GET /api/environments/{name}/agent-prompt` 拉取 environment prompt 原文拼入 system prompt。
- `sandbox` 是强依赖：如果 environment prompt 缺失、为空或请求失败，请求直接失败，并输出带 `agentKey/chatId/runId/environmentId` 的日志。
- `all-agents`：注入全部已注册 agent 的 YAML 风格头部摘要，只保留 `key/name/role/description/mode/modelKey/tools/skills/sandbox` 等高信号字段。

**文件格式与优先级：**

- `agents/` 支持扁平 YAML（`<key>.yml/.yaml`）与目录化 Agent（`<key>/agent.yml`）；推荐目录化布局。
- 目录中若出现旧 `*.json` 会直接 fail-fast。
- 同 basename 冲突时优先 `.yml`，并忽略对应 `.yaml`。
- 前 4 行固定为 `key`、`name`、`role`、`description`，且必须从第 1 行开始、禁止前置空行 / 注释、必须是单行 inline value。
- 目录化 Agent 可附带可选文件：`SOUL.md`、`AGENTS.md`、`AGENTS.plain.md`、`AGENTS.react.md`、`AGENTS.plan.md`、`AGENTS.execute.md`、`AGENTS.summary.md`、`memory/memory.md`。
- 运行时 system prompt 合并顺序：`SOUL.md` → Runtime Context（按 `contextConfig.tags` 注入）→ `AGENTS.md` / stage 专属 `AGENTS.<stage>.md` → `memory/memory.md` → YAML stage `systemPrompt` → skills appendix → tool appendix。

**多行 Prompt 写法：**

- YAML 推荐使用 block scalar（`|` / `>`）。

**步骤上限：**

- `react.maxSteps` 控制 REACT 循环上限。
- `planExecute.maxSteps` 控制 PLAN_EXECUTE 执行阶段步骤上限。

## Models 目录（内部注册）

- 运行目录：`models/`（默认，可通过 `agent.models.external-dir` 覆盖）。
- 不再内置同步 `models/`；可从 `example/models/` 复制到外置目录。
- 热加载：目录变更会触发模型刷新，并按 `modelKey` 依赖精准刷新受影响 agent。
- 文件格式：每个模型一个 YAML（建议 `models/<modelKey>.yml`）。
- 必填字段：`key`、`provider`、`protocol`、`modelId`。
- 常用字段：`isReasoner`、`isFunction`、`maxTokens`、`maxInputTokens`、`maxOutputTokens`。
- 计费字段：`pricing.promptPointsPer1k`、`pricing.completionPointsPer1k`、`pricing.perCallPoints`、`pricing.priceRatio`、`pricing.tiers[]`。
- 协议枚举：`OPENAI`、`ANTHROPIC`（当前 `ANTHROPIC` 仅预留，未实现时会在模型加载阶段拒绝）。
- 约定：`10000 积分 = 1 RMB`，按每 `1K tokens` 计价；运行时只做配置解析与透传，不内置财务结算逻辑。

## Agent 模式行为

### ONESHOT

单轮直答；若配置工具则允许单轮工具调用后输出最终答案。

### REACT

最多 N 轮循环（默认 6）：思考 → 调 1 个工具 → 观察结果，直到给出最终答案。每轮最多 1 个工具。

### PLAN_EXECUTE

plan 阶段按 `planExecute.plan.deepThinking` 分支：

- `deepThinking=false`：单回合 `agent-plan-generate`，关闭 reasoning，`tool_choice=required`，必须调用 `_plan_add_tasks_`。
- `deepThinking=true`：两回合公开流式：
  1. `agent-plan-draft`：开启 reasoning，`tool_choice=none`，只输出思考与规划正文。
  2. `agent-plan-generate`：关闭 reasoning，`tool_choice=required`，仅允许调用 `_plan_add_tasks_`。

execute 阶段每轮最多 1 个工具，完成后在更新回合调用 `_plan_update_task_`（失败可修复 1 次）。

任务状态集合：`init` / `completed` / `failed` / `canceled`（历史 `in_progress` 仅兼容读取并映射为 `init`）。`failed` 为中断状态：任务被更新为 `failed` 后立即停止执行。

## Tool 系统

### 工具文件类型

`tools/` 目录下的文件按后缀区分三种类型：

| 后缀 | ToolKind | 说明 |
|---|---|---|
| `.backend` | `BACKEND` | 后端工具，模型通过 Function Calling 调用。`description` 用于 OpenAI tool schema，`after_call_hint` 用于注入 system prompt 的“工具调用后推荐指令”章节。 |
| `.action` | `ACTION` | 动作工具，触发前端行为（如主题切换、烟花特效）。不等待 `/api/submit`，直接返回 `"OK"`。 |
| `.frontend` | `FRONTEND` | 前端工具定义文件，触发 UI 渲染并等待 `/api/submit` 提交；实际渲染内容由 `viewports/` 下 `.html/.qlc` 文件提供。 |

工具定义文件为单文件单工具 YAML 对象。前 4 行固定为 `name`、`label`、`description`、`type`；旧的 `tools[]` 聚合文件不再支持。工具名冲突策略：冲突项会被跳过，其它项继续生效。

若工具 YAML 顶层包含 `scaffold: true`，则仅作为目录化 Agent 的占位脚手架，不会被运行时注册。

### toolConfig 继承规则

- 顶层 `toolConfig.backends/frontends/actions` 定义默认工具集合。
- 各 stage 可通过自身 `toolConfig` 覆盖：缺失则继承顶层，显式 `null` 则清空。
- `PLAN_EXECUTE` 强制工具（`_plan_add_tasks_` / `_plan_update_task_`）不受 `toolConfig: null` 影响。

### 前端 tool 提交协议

- SSE `tool.start` / `tool.snapshot` 会包含：`toolType`（html/qlc）、`viewportKey`（viewport key）、`toolTimeout`（超时毫秒）。
- 默认等待超时 5 分钟（`agent.tools.frontend.submit-timeout-ms`）。
- `POST /api/submit` 请求体：`runId` + `toolId` + `params`。
- 前端工具返回值提取规则：直接回传 `params`（若为 `null` 则回传 `{}`）。

### Action 行为规则

- Action 触发 `action.start` 后不等待提交，直接返回 `"OK"` 给模型。
- 事件顺序：`action.start` → `action.args`（可多次）→ `action.end` → `action.result`。
- `action.end` 必须紧跟该 action 的最后一个 `action.args`，不能延后到下一个 call 或 `action.result` 前补发。

### 内置工具

- `_bash_`：Shell 命令执行，需显式配置 `allowed-commands` 与 `allowed-paths` 白名单。
- `container_hub_bash`：在 Container Hub 沙箱环境中执行命令，受 `sandboxConfig` 与 `agent.tools.container-hub.*` 控制。
- `datetime`：获取当前或偏移后的日期时间；支持可选 `timezone` 与链式 `offset`，输出包含农历。`offset` 中 `M=月`、`m=分钟`，例如 `+10M+25D`、`+1D-3H+20m`。
- `mock_city_weather`：模拟城市天气数据。
- `agent_file_create`：创建 / 更新 agent YAML 文件。

### 工具参数模板

支持 `{{tool_name.field+Nd}}` 格式的日期运算和链式引用。

### 演进方向

- Frontend / Action 当前仍是正式支持的第一类工具能力，现行 REST / SSE / submit 契约均以此为准。
- CLI 模式替代 frontend / action 属于后续演进方向；在明确切换前，不以过渡实现替代本节规范。

## Skills 系统

### 目录结构

```text
skills/<skill-id>/
├── SKILL.md          # 必须，含 frontmatter（name/description）+ 正文指令
├── scripts/          # 可选，Python/Bash 脚本
├── references/       # 可选，参考资料
└── assets/           # 可选，静态资源
```

- `skill-id` 取目录名（小写归一化）。
- `SKILL.md` frontmatter 格式：`name: "显示名"` / `description: "描述"`。
- `SKILL.md` frontmatter 可额外带 `scaffold: true`，表示这是目录脚手架占位，不注入运行时技能目录。
- 正文作为 LLM prompt 注入，超过 `max-prompt-chars`（默认 8000）时截断。

### skillConfig 配置

Agent Definition 文件中引用 skills：

```yaml
skillConfig:
  skills: ["docx", "screenshot"]
```

运行时，技能目录摘要注入 system prompt；skill 正文用于给模型提供操作手册和命令模板，不再依赖专用脚本执行工具。

- 热加载：`skills/` 目录变更仅刷新 `SkillRegistryService`，不触发 agent reload；reload 后新 run 会读取新技能内容。
- 目录化 Agent 可放置 per-agent `skills/`，作为该 agent 私有 skill 资源。

### Prompt 注入定制

通过 `runtimePrompts.skill` 可自定义注入头：

- `catalogHeader`：技能目录标题（默认：“可用 skills（目录摘要，按需使用，不要虚构不存在的 skill 或脚本）:”）
- `disclosureHeader`：完整说明标题
- `instructionsLabel`：指令字段标签

### 官方示例 Skills

以下示例资源位于 `example/skills/`，可通过 `example/install-example-*` 覆盖复制到外部运行目录：

| skill-id | 说明 |
|----------|------|
| `screenshot` | 截图流程示例（含脚本 smoke test） |
| `docx` | Word 文档读写、内容提取、转换与结构化生成。 |
| `pptx` | PPT / PPTX 读取、编辑、从提纲生成幻灯片。 |
| `slack-gif-creator` | GIF 动画创建 |

## Schedules 系统

### 目录结构

```text
schedules/<schedule-id>.yml
```

- 每个文件仅定义一个 cron 计划任务。
- 头部前两行固定为 `name: ...`、`description: ...`，且二者都必须是单行 inline value。
- 必填字段：`cron`、`agentKey`、`query.message`。
- `teamId` 可选；若填写，`agentKey` 必须属于该 team。
- `environment.zoneId` 可选；不再支持顶层 `zoneId`。
- `query` 必须是对象；支持字段：`requestId`、`chatId`、`role`、`message`、`references`、`params`、`scene`、`hidden`，其中 `message` 必填。
- `query.stream` 不支持；`query.agentKey` / `query.teamId` 也不支持，仍使用顶层字段。
- 不再支持旧扁平格式：顶层字符串 `query`、顶层 `params`、仅配置 `teamId`。
- 启动时会同步内置 `src/main/resources/schedules/**` 到运行目录 `schedules/`。
- 热加载：仅监听运行目录 `schedules/` 的文件变化，并做增量重编排。
- 触发执行：内部构造一次 `QueryRequest`（`stream=false`）；若配置 `query.chatId` 则继续该会话，否则新建 UUID。

## Viewport 系统

### /api/viewport 端点契约

```text
GET /api/viewport?viewportKey=<key>
```

- `viewportKey` 必填。
- 返回：
  - `html` 文件：`data = {"html":"<...>"}`
  - `qlc` 文件：`data` 直接是文件内 JSON 对象
- `viewportKey` 不存在时返回 `404`。

### 支持后缀

| 文件后缀 | ViewportType | 说明 |
|----------|--------------|------|
| `.html` | `HTML` | 静态 HTML 渲染 |
| `.qlc` | `QLC` | QLC 表单 schema |

### Viewport 输出协议

Agent 通过代码块协议输出 viewport 渲染指令：

```viewport
type=html, key=show_weather_card
{
  "city": "Shanghai",
  "date": "2026-02-13",
  "temperatureC": 22
}
```

## Container Hub 三级沙箱生命周期

Container Hub 容器沙箱支持三种生命周期级别，通过 `sandboxConfig.level` 或全局默认 `default-sandbox-level` 配置。

### 级别定义

| 级别 | session_id 命名 | 生命周期 | 并发策略 |
|------|----------------|---------|---------|
| `RUN` | `run-{runId}` | 随 run 创建 / 销毁（异步延迟销毁） | 无共享状态，每个 run 独立 |
| `AGENT` | `agent-{agentKey}` | 同 agentKey 复用，空闲超时后驱逐 | `ConcurrentHashMap.compute()` + `AtomicInteger` 引用计数 |
| `GLOBAL` | `global-singleton` | 应用生命周期内单例，shutdown 时销毁 | `synchronized` + double-check locking |

### 挂载目录映射

默认仅挂载 5 个真实容器路径，其余平台目录需通过 `sandboxConfig.extraMounts` 显式声明。

| 容器内路径 | RUN 宿主路径 | AGENT / GLOBAL 宿主路径 | 默认策略 | 默认模式 | 配置键（为空时 fallback） |
|-----------|-------------|-------------------------|----------|----------|--------------------------|
| `/workspace` | `{chatDataDir}/{chatId}` | `{chatDataDir}` | 默认挂载 | `rw` | `memory.chats.dir` |
| `/root` | `{rootDir}` | `{rootDir}` | 默认挂载 | `rw` | `agent.root.external-dir` |
| `/skills` | `{skillsDir}` | `{skillsDir}` | 默认挂载 | `ro` | `agent.skills.external-dir` |
| `/pan` | `{panDir}` | `{panDir}` | 默认挂载 | `rw` | `agent.pan.external-dir` |
| `/agent` | `{agentsDir}/{agentKey}` | `{agentsDir}/{agentKey}` | 默认挂载；仅目录化 agent 存在时挂载 | `ro` | `agent.agents.external-dir` |
| `/tools` | `{toolsDir}` | `{toolsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.tools.external-dir` |
| `/agents` | `{agentsDir}` | `{agentsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.agents.external-dir` |
| `/models` | `{modelsDir}` | `{modelsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.models.external-dir` |
| `/viewports` | `{viewportsDir}` | `{viewportsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.viewports.external-dir` |
| `/viewport-servers` | `{viewportServersDir}` | `{viewportServersDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.viewport-servers.registry.external-dir` |
| `/teams` | `{teamsDir}` | `{teamsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.teams.external-dir` |
| `/schedules` | `{schedulesDir}` | `{schedulesDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.schedule.external-dir` |
| `/mcp-servers` | `{mcpServersDir}` | `{mcpServersDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.mcp-servers.registry.external-dir` |
| `/providers` | `{providersDir}` | `{providersDir}` | `extraMounts` 按需；敏感目录 | 显式 `mode` | `agent.providers.external-dir` |
| `/chats` | `{chatDataDir}` | `{chatDataDir}` | `extraMounts` 按需 | 显式 `mode` | `memory.chats.dir` |
| `/OWNER.md` | `{agentsDir}/../OWNER.md` | `{agentsDir}/../OWNER.md` | `extraMounts` 按需；单文件 | 显式 `mode` | `agent.agents.external-dir` 的父目录 |

### Agent Definition 中的 sandboxConfig

通过 `sandboxConfig` 配置沙箱参数（`environmentId`、`level`、`extraMounts`）。
完整配置示例、平台简写表、挂载原则和约束规则详见 [docs/sandbox-config-reference.md](./docs/sandbox-config-reference.md)。

### 并发与销毁策略

- **RUN**：`closeQuietly` 触发异步延迟销毁（`destroyQueueDelayMs` 后调用 `stopSession`）。
- **AGENT**：`closeQuietly` 减少引用计数；引用归零后调度空闲驱逐定时器；驱逐时二次检查引用计数和空闲时间。
- **GLOBAL**：`closeQuietly` 为 no-op；仅在 `DisposableBean.destroy()` 时停止。
- **shutdown**：`destroy()` 关闭调度器，遍历停止所有 agent 和 global 会话。

## SSE 事件契约（最新）

### 1. 基础字段（所有 SSE 事件）

- 必带字段：`seq`, `type`, `timestamp`
- 不再输出：`rawEvent`

### 2. 输入与会话事件

- `request.query`：`requestId`, `chatId`, `role`, `message`, `agentKey?`, `references?`, `params?`, `scene?`, `stream?`
- `request.upload`：`requestId`, `chatId?`, `upload:{type,name,sizeBytes,mimeType,sha256?}`
- `request.submit`：`requestId`, `chatId`, `runId`, `toolId`, `payload`, `viewId?`
- `request.steer`：`requestId?`, `chatId`, `runId`, `steerId`, `message`, `role=user`
- `chat.start`：`chatId`, `chatName?`（仅该 chat 首次 run 发送一次）
- `chat.update`：当前不发送

### 3. 计划、运行与任务事件

- `plan.create`：`planId`, `chatId`, `plan`
- `plan.update`：`planId`, `chatId`, `plan`（总是带 `chatId`）
- `run.start`：`runId`, `chatId`, `agentKey`
- `run.complete`：`runId`, `finishReason?`
- `run.cancel`：`runId`
- `run.error`：`runId`, `error`
- `task.*`：仅在“已有 plan 且显式 `task.start` 输入”时出现；不自动创建 task

### 4. 推理与内容事件

- `reasoning.start`：`reasoningId`, `runId`, `taskId?`
- `reasoning.delta`：`reasoningId`, `delta`
- `reasoning.end`：`reasoningId`
- `reasoning.snapshot`：`reasoningId`, `text`, `taskId?`
- `content.start`：`contentId`, `runId`, `taskId?`
- `content.delta`：`contentId`, `delta`
- `content.end`：`contentId`
- `content.snapshot`：`contentId`, `text`, `taskId?`

### 5. 工具与动作事件

- `tool.start`：`toolId`, `runId`, `taskId?`, `toolName?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`
- `tool.args`：`toolId`, `delta`, `chunkIndex?`（字段名保持 `delta`，不使用 `args`）
- `tool.end`：`toolId`
- `tool.result`：`toolId`, `result`
- `tool.snapshot`：`toolId`, `toolName?`, `taskId?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`, `arguments?`
- `action.start`：`actionId`, `runId`, `taskId?`, `actionName?`, `description?`
- `action.args`：`actionId`, `delta`
- `action.end`：`actionId`
- `action.param`：`actionId`, `param`
- `action.result`：`actionId`, `result`
- `action.snapshot`：`actionId`, `actionName?`, `taskId?`, `description?`, `arguments?`

### 6. 来源事件

- `source.snapshot`：`sourceId`, `runId?`, `taskId?`, `icon?`, `title?`, `url?`

### 7. 补充行为约束

- 无活跃 task 出错时：只发 `run.error`（不补 `task.fail`）。
- plain 模式（当前无 plan）不应出现 `task.*`，叶子事件直接归属 `run`。
- `GET /api/chat` 历史事件需与新规则对齐；历史使用 `*.snapshot` 替代 `start/end/delta/args` 细粒度流事件，并保留 `tool.result` / `action.result`。
- 历史里 `run.complete` 每个 run 都保留，`chat.start` 仅首次一次。
- `/api/query` 在流式输出结束时追加传输层终止帧 `data:[DONE]`；该 sentinel 不属于 Event Model v2 业务事件，也不写入 chat 历史事件。
- `RenderQueue` 只影响传输 flush 行为，不改变上述业务事件类型与字段契约。

## Chat Memory V3.1（JSONL）

以下为 V3 格式基础 + V3.1 增量改进的完整规范。

- 存储文件：`chats/{chatId}.json`，JSONL 格式，**一行一个 step**，逐步增量写入。
- 行类型通过 `_type` 字段区分：
  - `"query"`：用户原始请求行。必带 `chatId`、`runId`、`updatedAt`、`query`。
  - `"step"`：一个执行步骤行。必带 `chatId`、`runId`、`_stage`、`_seq`、`updatedAt`、`messages`；可选 `taskId`、`system`、`plan`。
- `_stage` 标识步骤阶段：`"oneshot"` / `"react"` / `"plan"` / `"execute"` / `"summary"`。
- `_seq` 全局递增序号，标识 run 内的步骤顺序。
- `query` 保存完整 query 结构（`requestId/chatId/agentKey/role/message/references/params/scene/stream`）。
- `system` 快照规则：每个 run 的第一个 step 写入；stage 切换且 system 变化时再写入；后续 step 如果 system 未变化则省略。
- `messages` 采用 OpenAI 风格：
  - `role=user`：`content[]`（text parts）+ `ts`
  - `role=assistant`：三种快照形态之一：`content[]` / `reasoning_content[]` / `tool_calls[]`
  - `role=tool`：`name` + `tool_call_id` + `content[]` + `ts`
- assistant / tool 扩展字段支持：`_reasoningId`、`_contentId`、`_msgId`、`_toolId`、`_actionId`、`_timing`、`_usage`。
- action / tool 判定：通过 `memory.chats.action-tools` 白名单；命中写 `_actionId`，否则写 `_toolId`。
- memory 回放约束：`reasoning_content` **不回传**给下一轮模型上下文。
- 滑动窗口：`k=20` 单位仍然是 **run**；`trimToWindow` 按 `runId` 分组，保留最近 `k` 个 run 的所有行。

### V3.1 增量改进

#### _msgId

- 新增 `_msgId`（格式 `m_xxxxxxxx`，8 位 hex）标识同一 LLM 响应拆分的多条 assistant 消息。
- 同一模型回复中的 reasoning、content、tool_calls 消息共享相同 `_msgId`。
- tool result 到来后，下一个 reasoning / content delta 会重新生成 `_msgId`。

#### tool_calls 拆分规则

- 每条 `role=assistant` 的 `tool_calls` 数组只含 **1 个**工具调用。
- 并行多工具调用拆分为多条 assistant 消息，通过共享 `_msgId` 关联。

#### _toolId / _actionId 位置

- `_toolId` 和 `_actionId` 写入 `StoredMessage` 外层（与 `_reasoningId`、`_contentId` 同级）。
- `StoredToolCall` 内层的 `_toolId` / `_actionId` 仅用于反序列化旧 V3 数据，新数据不再写入。
- 读取时先查外层，再 fallback 内层（兼容旧数据）。

#### _toolId 生成规则

| 工具类型 | 生成规则 |
|----------|---------|
| backend（`type=function`） | 直接使用 LLM 原始 `tool_call_id`（如 `call_b7332997a5b1490ca7195293`） |
| frontend（`type=frontend`） | `t_` + 8 位 hex（系统生成） |
| action（`type=action`） | `a_` + 8 位 hex（系统生成） |

#### ID 前缀简化

| ID 类型 | 旧前缀 | 新前缀 |
|---------|--------|--------|
| reasoningId | `reasoning_` | `r_` |
| contentId | `content_` | `c_` |
| toolId (frontend) | `tool_` | `t_` |
| actionId | `action_` | `a_` |
| msgId | (新增) | `m_` |

SSE 事件中的 reasoningId / contentId 同步使用新前缀格式：`{runId}_r_{seq}` / `{runId}_c_{seq}`。

#### _usage 真实填充

- 通过 `stream_options.include_usage=true` 请求 LLM provider 返回真实 usage 数据。
- `LlmDelta` record 新增 `Map<String, Object> usage` 字段，SSE parser 解析最后一个 chunk 的 usage。
- usage 通过管道穿透：`LlmDelta` → `AgentDelta` → `StepAccumulator.capturedUsage` → `RunMessage` → `StoredMessage._usage`。
- 不再写入 placeholder null 值；当 LLM 未返回 usage 时 `_usage` 仍使用默认占位结构。

## Configuration

主配置事实源：`src/main/resources/application.yml`。结构化覆盖配置来自 `configs/`，通过启动期扫描加载。

### Spring / Server

| 项 | 默认值 | 说明 |
|----|--------|------|
| `server.port` | `8080` | HTTP 端口（环境变量 `SERVER_PORT`） |
| `spring.application.name` | `springai-agent-platform` | 服务名 |
| `CONFIGS_DIR` | `./configs` 或 `/opt/configs` | 结构化配置目录；显式设置时优先生效 |

### 核心环境变量

#### Agent Catalog / Model / Viewport / Data

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENTS_DIR` | `agent.agents.external-dir` | `agents` | Agent Definition 目录 |
| `AGENT_AGENTS_REFRESH_INTERVAL_MS` | `agent.agents.refresh-interval-ms` | `10000` | Agent 目录刷新间隔（ms） |
| `TEAMS_DIR` | `agent.teams.external-dir` | `teams` | Team 定义目录 |
| `PROVIDERS_DIR` | `agent.providers.external-dir` | `providers` | Provider YAML 定义目录 |
| `MODELS_DIR` | `agent.models.external-dir` | `models` | Model YAML 定义目录 |
| `AGENT_MODELS_REFRESH_INTERVAL_MS` | `agent.models.refresh-interval-ms` | `30000` | Model 目录刷新间隔（ms） |
| `MCP_SERVERS_DIR` | `agent.mcp-servers.registry.external-dir` | `mcp-servers` | MCP server 注册目录 |
| `VIEWPORT_SERVERS_DIR` | `agent.viewport-servers.registry.external-dir` | `viewport-servers` | Viewport server 注册目录 |
| `VIEWPORTS_DIR` | `agent.viewports.external-dir` | `viewports` | Viewport 目录 |
| `AGENT_VIEWPORTS_REFRESH_INTERVAL_MS` | `agent.viewports.refresh-interval-ms` | `30000` | Viewport 刷新间隔（ms） |
| `DATA_DIR` | `agent.data.external-dir` | `data` | 静态文件目录 |

#### H2A / Tools / Skills / Schedule

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_H2A_RENDER_FLUSH_INTERVAL_MS` | `agent.h2a.render.flush-interval-ms` | `0` | H2A RenderQueue 时间窗口刷新 |
| `AGENT_H2A_RENDER_MAX_BUFFERED_CHARS` | `agent.h2a.render.max-buffered-chars` | `0` | H2A RenderQueue 字符阈值刷新 |
| `AGENT_H2A_RENDER_MAX_BUFFERED_EVENTS` | `agent.h2a.render.max-buffered-events` | `0` | H2A RenderQueue 事件数阈值刷新 |
| `AGENT_H2A_RENDER_HEARTBEAT_PASS_THROUGH` | `agent.h2a.render.heartbeat-pass-through` | `true` | 心跳是否强制透传 |
| `TOOLS_DIR` | `agent.tools.external-dir` | `tools` | 工具定义文件目录 |
| `AGENT_TOOLS_REFRESH_INTERVAL_MS` | `agent.tools.refresh-interval-ms` | `30000` | 工具目录刷新间隔（ms） |
| `AGENT_TOOLS_FRONTEND_SUBMIT_TIMEOUT_MS` | `agent.tools.frontend.submit-timeout-ms` | `300000` | 前端工具提交等待超时（ms） |
| `AGENT_TOOLS_AGENT_FILE_CREATE_DEFAULT_SYSTEM_PROMPT` | `agent.tools.agent-file-create.default-system-prompt` | `你是通用助理，回答要清晰和可执行。` | `agent_file_create` 默认 system prompt |
| `AGENT_BASH_WORKING_DIRECTORY` | `agent.tools.bash.working-directory` | 项目运行根目录（通常为 `configs/` 上级目录） | Bash 工具工作目录 |
| `AGENT_BASH_ALLOWED_PATHS` | `agent.tools.bash.allowed-paths` | （空） | Bash 工具路径白名单（逗号分隔） |
| `AGENT_BASH_ALLOWED_COMMANDS` | `agent.tools.bash.allowed-commands` | （空 = 拒绝执行） | Bash 允许命令列表（逗号分隔） |
| `AGENT_BASH_PATH_CHECKED_COMMANDS` | `agent.tools.bash.path-checked-commands` | （空 = 默认等于 allowed-commands） | 启用路径校验的命令列表 |
| `AGENT_BASH_PATH_CHECK_BYPASS_COMMANDS` | `agent.tools.bash.path-check-bypass-commands` | （空 = 默认关闭） | 跳过路径校验的命令列表 |
| `AGENT_BASH_SHELL_FEATURES_ENABLED` | `agent.tools.bash.shell-features-enabled` | `false` | Bash 高级 shell 语法开关 |
| `AGENT_BASH_SHELL_EXECUTABLE` | `agent.tools.bash.shell-executable` | `bash` | Shell 模式执行器 |
| `AGENT_BASH_SHELL_TIMEOUT_MS` | `agent.tools.bash.shell-timeout-ms` | `10000` | Shell 模式超时（ms） |
| `AGENT_BASH_MAX_COMMAND_CHARS` | `agent.tools.bash.max-command-chars` | `16000` | Bash 命令最大字符数 |
| `AGENT_CONTAINER_HUB_ENABLED` | `agent.tools.container-hub.enabled` | `false` | 是否启用 Container Hub 沙箱 |
| `AGENT_CONTAINER_HUB_BASE_URL` | `agent.tools.container-hub.base-url` | `http://127.0.0.1:8080` | Container Hub 服务地址 |
| `AGENT_CONTAINER_HUB_AUTH_TOKEN` | `agent.tools.container-hub.auth-token` | （空） | Container Hub Bearer Token |
| `AGENT_CONTAINER_HUB_DEFAULT_ENVIRONMENT_ID` | `agent.tools.container-hub.default-environment-id` | （空） | 默认环境 ID |
| `AGENT_CONTAINER_HUB_REQUEST_TIMEOUT_MS` | `agent.tools.container-hub.request-timeout-ms` | `30000` | Container Hub HTTP 调用超时（ms） |
| `AGENT_CONTAINER_HUB_DEFAULT_SANDBOX_LEVEL` | `agent.tools.container-hub.default-sandbox-level` | `run` | 全局默认沙箱级别 |
| `AGENT_CONTAINER_HUB_AGENT_IDLE_TIMEOUT_MS` | `agent.tools.container-hub.agent-idle-timeout-ms` | `600000` | agent 级别空闲驱逐超时（ms） |
| `AGENT_CONTAINER_HUB_DESTROY_QUEUE_DELAY_MS` | `agent.tools.container-hub.destroy-queue-delay-ms` | `5000` | run 级别异步销毁延迟（ms） |
| `ROOT_DIR` | `agent.root.external-dir` | `root` | 容器 `/root` 对应的 runner 根目录 |
| `PAN_DIR` | `agent.pan.external-dir` | `pan` | 容器 `/pan` 对应的 runner 目录 |
| `SKILLS_MARKET_DIR` | `agent.skills.external-dir` | `skills-market` | 技能目录 |
| `AGENT_SKILLS_REFRESH_INTERVAL_MS` | `agent.skills.refresh-interval-ms` | `30000` | 技能刷新间隔（ms） |
| `AGENT_SKILLS_MAX_PROMPT_CHARS` | `agent.skills.max-prompt-chars` | `8000` | 技能 prompt 最大字符数 |
| `SCHEDULES_DIR` | `agent.schedule.external-dir` | `schedules` | 计划任务目录 |
| `AGENT_SCHEDULE_ENABLED` | `agent.schedule.enabled` | `true` | 计划任务总开关 |
| `AGENT_SCHEDULE_DEFAULT_ZONE_ID` | `agent.schedule.default-zone-id` | 系统时区 | 计划任务默认时区 |
| `AGENT_SCHEDULE_POOL_SIZE` | `agent.schedule.pool-size` | `4` | 计划任务线程池大小 |

#### MCP / Viewport Server / Auth / Memory / Logging

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_MCP_SERVERS_ENABLED` | `agent.mcp-servers.enabled` | `true` | MCP server 总开关 |
| `AGENT_MCP_SERVERS_PROTOCOL_VERSION` | `agent.mcp-servers.protocol-version` | `2025-06` | MCP 协议版本 |
| `AGENT_MCP_SERVERS_CONNECT_TIMEOUT_MS` | `agent.mcp-servers.connect-timeout-ms` | `3000` | MCP 连接超时 |
| `AGENT_MCP_SERVERS_RETRY` | `agent.mcp-servers.retry` | `1` | MCP 初始重试次数 |
| `AGENT_MCP_SERVERS_RECONNECT_INTERVAL_MS` | `agent.mcp-servers.reconnect-interval-ms` | `60000` | 不可用 server 的自动重连间隔 |
| `AGENT_VIEWPORT_SERVERS_ENABLED` | `agent.viewport-servers.enabled` | `true` | Viewport server 总开关 |
| `AGENT_VIEWPORT_SERVERS_PROTOCOL_VERSION` | `agent.viewport-servers.protocol-version` | `2025-06` | Viewport server 协议版本 |
| `AGENT_VIEWPORT_SERVERS_CONNECT_TIMEOUT_MS` | `agent.viewport-servers.connect-timeout-ms` | `3000` | Viewport server 连接超时 |
| `AGENT_VIEWPORT_SERVERS_RETRY` | `agent.viewport-servers.retry` | `1` | Viewport server 初始重试次数 |
| `AGENT_VIEWPORT_SERVERS_RECONNECT_INTERVAL_MS` | `agent.viewport-servers.reconnect-interval-ms` | `60000` | Viewport server 自动重连间隔 |
| `AGENT_AUTH_ENABLED` | `agent.auth.enabled` | `true` | JWT 认证开关 |
| `AGENT_AUTH_JWKS_URI` | `agent.auth.jwks-uri` | （空） | JWKS 地址 |
| `AGENT_AUTH_ISSUER` | `agent.auth.issuer` | （空） | JWT issuer |
| `AGENT_AUTH_JWKS_CACHE_SECONDS` | `agent.auth.jwks-cache-seconds` | （空） | JWKS 缓存秒数 |
| `CHAT_IMAGE_TOKEN_SECRET` | `agent.chat-image-token.secret` | （空） | 图片令牌签名密钥（为空则 token 机制禁用） |
| `CHAT_IMAGE_TOKEN_PREVIOUS_SECRETS` | `agent.chat-image-token.previous-secrets` | （空） | 历史密钥列表（逗号分隔），用于密钥轮换验证 |
| `CHAT_IMAGE_TOKEN_TTL_SECONDS` | `agent.chat-image-token.ttl-seconds` | `86400` | 图片令牌过期秒数 |
| `CHAT_IMAGE_TOKEN_DATA_TOKEN_VALIDATION_ENABLED` | `agent.chat-image-token.data-token-validation-enabled` | `true` | `/api/data` 的 `t` 参数校验开关 |
| `CHATS_DIR` | `memory.chats.dir` | `./chats` | 聊天记忆目录 |
| `MEMORY_CHATS_K` | `memory.chats.k` | `20` | 滑动窗口大小（按 run） |
| `MEMORY_CHATS_CHARSET` | `memory.chats.charset` | `UTF-8` | 记忆文件编码 |
| `MEMORY_CHATS_ACTION_TOOLS` | `memory.chats.action-tools` | （空） | action 工具白名单 |
| `LOGGING_AGENT_LLM_INTERACTION_ENABLED` | `logging.agent.llm.interaction.enabled` | `true` | LLM 交互日志开关 |
| `LOGGING_AGENT_LLM_INTERACTION_MASK_SENSITIVE` | `logging.agent.llm.interaction.mask-sensitive` | `true` | 日志脱敏开关 |

说明：`agent.auth.local-public-key` 仍支持 YAML 内嵌 PEM；推荐改用 `AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE` 或 `agent.auth.local-public-key-file` 指向独立 PEM 文件。

迁移说明（Breaking Change）：

- 旧键已禁用：`agent.catalog.*`、`agent.viewport.*`、`agent.capability.*`、`agent.skill.*`、`agent.team.*`、`agent.model.*`、`agent.mcp.*`、`memory.chat.*`。
- 旧环境变量已禁用：`AGENT_CONFIG_DIR`、`AGENT_AGENTS_EXTERNAL_DIR`、`AGENT_TEAMS_EXTERNAL_DIR`、`AGENT_MODELS_EXTERNAL_DIR`、`AGENT_PROVIDERS_EXTERNAL_DIR`、`AGENT_TOOLS_EXTERNAL_DIR`、`AGENT_SKILLS_EXTERNAL_DIR`、`AGENT_VIEWPORTS_EXTERNAL_DIR`、`AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR`、`AGENT_VIEWPORT_SERVERS_REGISTRY_EXTERNAL_DIR`、`AGENT_SCHEDULE_EXTERNAL_DIR`、`AGENT_DATA_EXTERNAL_DIR`、`MEMORY_CHATS_DIR` 等。
- 启动时若检测到旧键或旧变量，服务会直接失败；请按新命名迁移后再启动。

### CORS（主配置默认）

| 属性键 | 默认值 | 说明 |
|--------|-------|------|
| `agent.cors.enabled` | `false` | 默认关闭 CORS 过滤器 |
| `agent.cors.path-pattern` | `/api/**` | CORS 匹配路径 |
| `agent.cors.allowed-origin-patterns` | `http://localhost:8081` | 允许源（列表） |
| `agent.cors.allowed-methods` | `GET,POST,PUT,PATCH,DELETE,OPTIONS` | 允许方法（列表） |
| `agent.cors.allowed-headers` | `*` | 允许请求头 |
| `agent.cors.exposed-headers` | `Content-Type` | 暴露响应头 |
| `agent.cors.allow-credentials` | `false` | 是否允许凭证 |
| `agent.cors.max-age-seconds` | `3600` | 预检缓存秒数 |

### Provider 配置（通常在 `configs/providers/<provider>.yml`）

`agent.providers.<providerKey>` 支持：

- `base-url`
- `api-key`
- `defaultModel`（可选，作为 provider 默认 model）
- `protocols.<PROTOCOL>.endpoint-path`（可选，按线协议覆盖请求 endpoint 路径）

说明：

- provider 不再绑定 protocol；协议由 `models/*.yml` 中 `protocol` 字段决定。
- `OPENAI` 未显式配置 `protocols.OPENAI.endpoint-path` 时，会按 `base-url` 推导默认 completions 路径。
- provider 只负责连接信息与 endpoint 组织，不负责声明 Agent 使用哪个协议。

### Logging（主配置默认）

| 属性键 | 默认值 |
|--------|--------|
| `logging.level.root` | `INFO` |
| `logging.level.com.linlay.agentplatform` | `INFO` |
| `logging.level.com.linlay.agentplatform.service.LlmService` | `DEBUG` |
| `logging.level.com.linlay.agentplatform.service.OpenAiCompatibleSseClient` | `DEBUG` |
| `logging.level.com.linlay.agentplatform.service.LlmCallLogger` | `DEBUG` |
| `logging.level.com.linlay.agentplatform.llm.wiretap` | `DEBUG` |
| `logging.agent.request.enabled` | `true` |
| `logging.agent.auth.enabled` | `true` |
| `logging.agent.exception.enabled` | `true` |
| `logging.agent.tool.enabled` | `true` |
| `logging.agent.action.enabled` | `true` |
| `logging.agent.viewport.enabled` | `true` |
| `logging.agent.sse.enabled` | `false` |
| `logging.agent.llm.interaction.enabled` | `true` |

## H2A 流式传输说明

- H2A 的目标是“真实流式 + 可控缓冲 + 可取消”，而不是把“零缓冲”当成唯一正确答案。
- 当 `flush-interval-ms / max-buffered-chars / max-buffered-events` 全部为 `0` 时，`RenderQueue` 退化为直通透传。
- 当任一阈值大于 `0` 时，允许在不改变业务事件顺序与语义的前提下进行有界缓冲。
- 终端事件 `run.complete / run.cancel / run.error` 与心跳始终具备更高 flush 优先级。
- steer / interrupt 走运行中控制面；它们改变的是上下文注入和取消行为，不改变 SSE 业务事件模型本身。

## 开发硬性要求（MUST）

### LLM 调用日志

所有大模型调用的完整日志必须打印到控制台：

- 每个 SSE delta（reasoning / content / tool_calls）逐条打印 `log.debug`。
- 工具调用 delta 打印 tool name、arguments 片段、finish_reason。
- `LlmService.appendDeltaLog` 带 `traceId / stage` 参数，`streamContent` / `streamContentRawSse` 均有逐 chunk debug 日志。
- 日志开关：`logging.agent.llm.interaction.enabled`（默认 `true`）。
- 脱敏开关：`logging.agent.llm.interaction.mask-sensitive`（默认 `true`），会脱敏 `authorization/apiKey/token/secret/password`。

## 设计原则

Agent 行为应由 LLM 推理和工具调用驱动（通过 prompt 引导），Java 层只负责编排、流式传输、预算控制、输入控制面和工具执行管理。

## 变更记录
