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
  → QueryController
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

### 核心技术能力

- **H2A 双队列流式管道** — steer / interrupt / render buffering 一体化控制，既支持近实时输出，也支持有界缓冲与可取消的人机协作。
- **定义驱动 + 依赖感知热重载** — YAML 驱动，按依赖精准刷新受影响 agent
- **System Prompt 多层合并管线** — `SOUL.md → Runtime Context → AGENTS.md → memory → YAML prompt → skills/tool appendix`
- **Container Hub 三级沙箱生命周期** — `RUN / AGENT / GLOBAL` 三级复用与销毁策略
- **SSE 事件契约** — Event Model v2 统一事件字段与历史回放约束
- **Chat Storage V3.1** — JSONL 增量落盘、按 run 滑动窗口、`_msgId` 关联
- **Agent Memory 混合召回** — SQLite + FTS5 + embedding 向量检索 + markdown/journal 双写
- **三种 Agent 执行模式** — `OneshotMode`、`ReactMode`、`PlanExecuteMode`
- **Backend / Frontend / Action 多类型工具系统** — 同一 Function Calling 协议下走不同执行路径
- **MCP Server 集成与可用性管理** — 目录式注册、可用性 gate、自动重连
- **JWT + Chat Image Token 双层鉴权** — `/api/*` 走 JWT，`/api/resource` 支持 HMAC-SHA256 签名
- **Budget / Compute Policy 资源限制** — run / model / tool 三层限制调用量与超时
- **Bash 工具安全模型** — 白名单命令、路径白名单、按命令分级路径校验
- **Schedule / Cron 定时编排** — YAML 定义 + Spring `CronTrigger` + 增量 reconcile
- **Remember 记忆抽取** — 从 chat 快照中调用 LLM 提炼长期记忆

### H2A 双队列流式管道

H2A 不是"零缓冲口号"，而是一个可控的流式传输层：

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

### 核心模块

| 包 | 职责 |
|---|------|
| `engine` | `DefinitionDrivenAgent` 主实现与 Agent 核心引擎入口 |
| `engine.definition` | `AgentRegistry`、YAML 定义加载、目录化 Agent prompt 文件、依赖索引与定义模型 |
| `engine.prompt` | `RuntimeContextPromptService`、`RuntimeContextTags`、`ToolAppend`、`SkillAppend` |
| `engine.mode` | `AgentMode`（sealed：`OneshotMode` / `ReactMode` / `PlanExecuteMode`）、`OrchestratorServices`、`StageSettings` |
| `engine.runtime` | `ExecutionContext`、`RunControl`、`RunInputBroker`、`RunLoopState`、`StepAccumulator`、`TurnTraceWriter` 等运行执行核心 |
| `engine.runtime.tool` | `ToolExecutionService`、`ToolInvokerRouter`、`LocalToolInvoker`、`McpToolInvoker`、`FrontendSubmitCoordinator` 等工具执行引擎 |
| `engine.sandbox` | `ContainerHubSandboxService`、`ContainerHubMountResolver`、`SandboxContextResolver`、`ContainerHubClient`、`SystemContainerHubBash` 等沙箱接入 |
| `engine.exception` | `BudgetExceededException`、`FatalToolExecutionException`、`FrontendSubmitTimeoutException`、`ModelTimeoutException`、`RunInterruptedException` |
| `engine.policy` | `RunSpec`、`ToolChoice`、`ComputePolicy`、`Budget` 等策略定义 |
| `engine.query` | `AgentQueryService`、`ActiveRunService` |
| `stream` | `StreamEventAssembler`、`StreamSseStreamer`、`RenderQueue`、H2A 传输整形 |
| `model` | `AgentRequest`、`ModelDefinition`、`ModelProtocol`、`ViewportType` |
| `model.api` | REST 契约：`ApiResponse`、`QueryRequest`、`SubmitRequest`、`SteerRequest`、`InterruptRequest`、`ChatDetailResponse` 等 |
| `llm` | `LlmService`、`OpenAiCompatibleSseClient`、`ProviderRegistryService`、`LlmCallLogger` |
| `chat` | `history / upload / asset / index / event / storage` 六类会话与资源子域；`ChatMessage` 与 `ArtifactEventPayload` 归属该域 |
| `memory` | Agent Memory、Remember、Embedding 与 memory tools |
| `integration` | MCP、Viewport、Remote Server 接入与重连 |
| `catalog` | Skill、Team、Schedule 轻量注册中心 |
| `tool` | `BaseTool`、`ToolRegistry`、`ToolFileRegistryService`、内置 `_bash_` / `_datetime_` 等通用工具与注册中心 |
| `security` | `ApiJwtAuthWebFilter`、`ChatImageTokenService`、JWT/JWKS 本地与远程校验 |
| `controller` | REST API：`AgentCatalogController`、`ChatController`、`FileController`、`MemoryController`、`QueryController`、`ScheduleController`、`ViewportController` |
| `config` | 应用配置：`RuntimeDirectoryHostPaths`、`ConfigDirectorySupport`、`DirectoryWatchService`、`@ConfigurationProperties` 子包 |
| `config/boot` | `ConfigDirectoryEnvironmentPostProcessor`：启动期外部配置目录加载 |

### 关键设计

- **定义驱动** — Agent 支持扁平 YAML（`agents/<key>.yml`）与目录化布局（`agents/<key>/agent.yml`），目录化布局可附带 `SOUL.md` / `AGENTS*.md` / `memory/` / per-agent `skills/` / `tools/`。
- **提示词分层管理** — 人格、角色知识、阶段指令、记忆、技能说明、工具说明拆成独立来源，运行时按顺序合并。
- **依赖感知热重载** — 基于 `WatchService + 防抖 + CatalogDiff + AgentDependencyIndex`，支持 `Provider → Model → Agent`、`MCP Tool → Agent`、`Tool → Agent` 级联刷新；`skills` 仅刷新技能注册表。
- **MCP 可用性门控** — `McpServerAvailabilityGate` 记录失败窗口，`McpReconnectOrchestrator` 定时重试 due servers，并只刷新受影响 agent。
- **响应格式统一** — 非 SSE 接口统一 `{"code": 0, "msg": "success", "data": {}}`；`/api/query` 结束时追加 `data:[DONE]` 传输层终止帧。
- **会话详情稳定契约** — `GET /api/chat` 的 `data` 字段固定为 `chatId/chatName/chatImageToken/rawMessages/events/plan/artifact/references`；`events` 必返，`rawMessages` 仅在 `includeRawMessages=true` 返回；其中 `data.artifact` 为聚合状态 `{items:[...]}`；历史 `events` 不再包含 `plan.update` / `artifact.publish`。

## Agent Definition 文件格式

Agent Definition 仅支持 YAML，推荐 `.yml`。前 4 行固定为 `key`、`name`、`role`、`description`。简化示例：

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
  backends: ["_bash_", "_datetime_"]
  frontends: ["show_weather_card"]
  actions: ["switch_theme"]
mode: ONESHOT
plain:
  systemPrompt: |
    系统提示词
    支持多行
```

### 配置继承与模式

- **modelConfig**：支持外层默认 + stage 内层覆盖；内层优先。`provider/modelId/protocol` 由 `registries/models/<modelKey>.yml` 解析。
- **toolConfig**：支持外层默认 + stage 覆盖；stage 显式 `null` 清空该 stage；`PLAN_EXECUTE` 强制工具不受 `null` 影响。
- **模式配置块**：`ONESHOT` → `plain.systemPrompt`；`REACT` → `react.systemPrompt`；`PLAN_EXECUTE` → `planExecute.plan/execute.systemPrompt`。

### contextConfig 七个 tag

| Tag | 说明 |
|-----|------|
| `system` | 注入 OS、Java 版本、时区、当前日期等系统环境信息 |
| `context` | 注入运行目录路径与当前会话上下文（chatId、runId、agentKey 等） |
| `owner` | 注入 owner 目录下所有 Markdown 文件内容 |
| `auth` | 注入 JWT 主体信息（subject、deviceId、scope 等） |
| `sandbox` | 注入沙箱环境信息与 Container Hub environment prompt |
| `all-agents` | 注入全部已注册 agent 的摘要（截断 12,000 字符） |
| `memory` | 注入 agent SQLite memory 摘要（按语义相关性或 importance 取 top-N） |

tag 名大小写不敏感。`sandbox`、`all-agents`、`memory` 仅在声明时才解析。tag 与 `.env` 目录配置、Docker 挂载无关。

### REST 端点契约

- `GET /api/agents`：返回 `AgentSummary[]`，顶层包含 `key/name/icon/description/role/meta`。
- `GET /api/teams`：返回 `TeamSummaryResponse[]`，字段包含 `teamId/name/icon/agentKeys/meta.invalidAgentKeys/meta.defaultAgentKey/meta.defaultAgentKeyValid`。
- `GET /api/skills?tag=...`：返回 `SkillSummary[]`，字段为 `key/name/description/meta.promptTruncated`。
- `GET /api/tools?tag=...&kind=backend|frontend|action`：返回 `ToolSummary[]`，字段为 `key/name/label/description/meta(kind/toolType/viewportKey/strict/sourceType/sourceKey)`。
- `GET /api/tool?toolName=...`：返回 `ToolDetail`，字段为 `key/name/label/description/afterCallHint/parameters/meta`。
- `GET /api/chats`：返回会话索引摘要，支持按 `agentKey` 与 `lastRunId` 增量查询。
- `GET /api/chat?chatId=...`：返回稳定结构 `chatId/chatName/chatImageToken/events/plan/artifact/references`；`includeRawMessages=true` 时额外返回 `rawMessages`。
- `POST /api/query`：启动一次 run；默认返回 SSE。未绑定 chat 的首个 query 必须显式携带 `agentKey`。
- `POST /api/upload`：申请本地上传位，请求体字段为 `requestId/chatId?/type/name/sizeBytes/mimeType/sha256?`，响应外层为 `ApiResponse<UploadResponse>`；若未传 `chatId`，后端会先生成 chatId 并创建空绑定 chat，其中短引用 ID 固定放在 `reference.id`。
- `POST /api/submit`：提交 frontend tool 的人机参数，请求体固定为 `runId + toolId + params`。
- `POST /api/steer`：运行中追加用户引导，请求体为 `SteerRequest`；当 run 仍处于活跃状态时会写入 steer 队列并在下一个模型回合前注入。
- `POST /api/interrupt`：运行中断，请求体为 `InterruptRequest`；成功后触发 `run.cancel`，并取消待处理 steer / frontend submit。
- `POST /api/remember`：从指定 `chatId` 的完整对话快照中抽取可长期保留的记忆，请求体为 `RememberRequest { requestId?, chatId }`；成功时返回 `ApiResponse<RememberResponse>`，字段包含 `accepted/status/requestId/chatId/memoryPath/memoryRoot/memoryCount/detail/promptPreview/items/stored`；`promptPreview` 会返回系统提示词、用户提示词预览、计数与采样；失败时 `RememberCaptureException` 由全局异常处理器映射为 HTTP `500`。
- `POST /api/learn`：学习接口预留，请求体为 `LearnRequest { requestId?, chatId, subjectKey? }`；当前始终返回 `ApiResponse<LearnResponse>`，其中 `accepted=false`、`status="not_connected"`。
- `GET /api/viewport?viewportKey=...`：返回 viewport 内容；本地未命中时可回退到 viewport server。
- `GET /api/resource?...`：提供静态文件访问，支持 chat image token 校验。
- `/api/tool` 未命中 `toolName`、`kind` 非法时均返回 HTTP `400`（`ApiResponse.failure`）。

完整 Schema、YAML 配置规则、tag 实现细节、sandboxConfig 配置、文件格式规则详见 [docs/agent-definition-reference.md](./docs/agent-definition-reference.md)。

## Models 目录（内部注册）

- 运行目录：`runtime/registries/models/`（默认，可通过 `agent.models.external-dir` 覆盖）。
- 不再内置同步 `models/`；可从 `example/models/` 复制到外置目录。
- 热加载：目录变更会触发模型刷新，并按 `modelKey` 依赖精准刷新受影响 agent。
- 文件格式：每个模型一个 YAML（建议 `registries/models/<modelKey>.yml`）。
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
| `.backend` | `BACKEND` | 后端工具，模型通过 Function Calling 调用。`description` 用于 OpenAI tool schema，`after_call_hint` 用于注入 system prompt 的"工具调用后推荐指令"章节。 |
| `.action` | `ACTION` | 动作工具，触发前端行为（如主题切换、烟花特效）。不等待 `/api/submit`，直接返回 `"OK"`。 |
| `.frontend` | `FRONTEND` | 前端工具定义文件，触发 UI 渲染并等待 `/api/submit` 提交；实际渲染内容由 `viewports/` 下 `.html/.qlc` 文件提供。 |

工具定义文件为单文件单工具 YAML 对象。前 4 行固定为 `name`、`label`、`description`、`type`；旧的 `tools[]` 聚合文件不再支持。工具名冲突策略：冲突项会被跳过，其它项继续生效。

若工具 YAML 顶层包含 `scaffold: true`，则仅作为目录化 Agent 的占位脚手架，不会被运行时注册。

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
- `_sandbox_bash_`：在 Container Hub 沙箱环境中执行命令，受 `sandboxConfig` 与 `agent.tools.container-hub.*` 控制。
- `_datetime_`：获取当前或偏移后的日期时间；支持可选 `timezone` 与链式 `offset`，输出包含农历。`offset` 中 `M=月`、`m=分钟`，例如 `+10M+25D`、`+1D-3H+20m`。
- `_memory_write_` / `_memory_read_` / `_memory_search_`：agent 持久化记忆工具（`memoryConfig.enabled=true` 时自动暴露）。
- `confirm_dialog`：前端确认对话框工具，声明位于 `src/main/resources/tools/confirm_dialog.yml`。

### 工具参数模板

支持 `{{tool_name.field+Nd}}` 格式的日期运算和链式引用。

### 演进方向

- Frontend / Action 当前仍是正式支持的第一类工具能力，现行 REST / SSE / submit 契约均以此为准。
- CLI 模式替代 frontend / action 属于后续演进方向；在明确切换前，不以过渡实现替代本节规范。

## Agent Memory 系统

- 现有文件型 `memory/memory.md` 仍保留，继续通过 `ExecutionContext.memoryPrompt` 注入，位置在 `AGENTS*.md` 之后、YAML stage prompt 之前。
- 新增 SQLite agent memory：目录化 agent 使用 `<agentDir>/memory.db`，扁平 agent 使用 `<agent.agents.external-dir>/<agentKey>/memory.db`。
- agent 级 `memoryConfig.enabled=true` 时，运行时自动暴露 memory tools；失败、取消、超时等非成功结束不会自动写入记忆。
- schema：单表 `MEMORIES` + `MEMORIES_FTS`（FTS5 外部内容模式）+ 触发器自动同步。
- 查询策略：FTS5 BM25 候选 + embedding 余弦相似度候选（可用时），两路 min-max 归一化后按权重融合。
- `dual-write-markdown=true` 且 agent 为目录化布局时，`_memory_write_` 会将新记忆追加写入 `memory/memory.md`；扁平 agent 不做 markdown 双写。
- embedding 依赖 OpenAI-compatible `/v1/embeddings`；provider 缺失、超时、维度不匹配或请求失败时自动退化为 FTS-only，不阻断工具调用。

### Remember Feature

- 工作流：`POST /api/remember` → 加载完整对话 → LLM 提炼记忆 → 写入 central memory SQLite 与 journal markdown。
- 自动 remember：成功 run 且 `memoryConfig.enabled=true` 并且 `agent.memory.auto-remember.enabled=true` 时触发。
- 典型失败场景：`agent.memory.remember.model-key` 未配置、model registry 未找到模型、LLM 超时、LLM 返回空字符串或非法 JSON；这些都会抛 `RememberCaptureException` 并返回 HTTP `500`。

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
- 配置了 `skillConfig.skills` 的 agent 会自动获得 `_sandbox_bash_`，无需再显式写入 `toolConfig.backends`。

- 热加载：`skills/` 目录变更仅刷新 `SkillRegistryService`，不触发 agent reload；reload 后新 run 会读取新技能内容。
- 目录化 Agent 可放置 per-agent `skills/`，作为该 agent 私有 skill 资源。

### Prompt 注入定制

通过 `runtimePrompts.skill` 可自定义注入头：

- `catalogHeader`：技能目录标题（默认："可用 skills（目录摘要，按需使用，不要虚构不存在的 skill 或脚本）:"）
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
- 不再同步任何内置 schedule 资源；内容完全来自运行目录 `schedules/` 或 `SCHEDULES_DIR` 覆盖目录。
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

默认挂载 5 个容器路径（`/workspace`、`/root`、`/skills`、`/pan`、`/agent`），其余平台目录需通过 `sandboxConfig.extraMounts` 显式声明。挂载原则：默认安全模式（`/skills` 与 `/agent` 只读，`/workspace`、`/root`、`/pan` 读写）；agent 就近原则（目录化 agent 优先挂载本地 skills）；按需显式原则（其余平台目录仅通过 extraMounts 恢复）。

通过 `sandboxConfig` 配置沙箱参数（`environmentId`、`level`、`extraMounts`）。
sandboxConfig YAML 配置示例、平台简写表、挂载原则与约束规则详见 [docs/agent-definition-reference.md](./docs/agent-definition-reference.md)。
挂载目录映射表详见 [docs/configuration-reference.md](./docs/configuration-reference.md)。

### 并发与销毁策略

- **RUN**：`closeQuietly` 触发异步延迟销毁（`destroyQueueDelayMs` 后调用 `stopSession`）。
- **AGENT**：`closeQuietly` 减少引用计数；引用归零后调度空闲驱逐定时器；驱逐时二次检查引用计数和空闲时间。
- **GLOBAL**：`closeQuietly` 为 no-op；仅在 `DisposableBean.destroy()` 时停止。
- **shutdown**：`destroy()` 关闭调度器，遍历停止所有 agent 和 global 会话。

## SSE 事件契约

### 基础字段（所有 SSE 事件）

- 必带字段：`seq`, `type`, `timestamp`
- 不再输出：`rawEvent`

### 输入与会话事件

- `request.query`：`requestId`, `chatId`, `role`, `message`, `agentKey?`（未绑定 chat 首个 query 必填）, `references?`（`Reference` 支持 `sandboxPath`）, `params?`, `scene?`, `stream?`
- `request.upload`：`requestId`, `chatId?`, `upload:{type,name,sizeBytes,mimeType,sha256?}`
- `request.submit`：`requestId`, `chatId`, `runId`, `toolId`, `payload`, `viewId?`
- `request.steer`：`requestId?`, `chatId`, `runId`, `steerId`, `message`, `role=user`
- `chat.start`：`chatId`, `chatName?`（仅该 chat 首次 run 发送一次）

### 计划、运行与任务事件

- `plan.create`：`planId`, `chatId`, `plan`
- `plan.update`：`planId`, `chatId`, `plan`（总是带 `chatId`）
- `run.start`：`runId`, `chatId`, `agentKey`
- `run.complete`：`runId`, `finishReason?`（仅成功完成）
- `run.cancel`：`runId`
- `run.error`：`runId`, `error`
  - `error`：`code`, `message`, `scope`, `category`, `diagnostics?`
- `task.*`：仅在"已有 plan 且显式 `task.start` 输入"时出现；不自动创建 task

### 推理与内容事件

- `reasoning.start`：`reasoningId`, `runId`, `taskId?`
- `reasoning.delta`：`reasoningId`, `delta`
- `reasoning.end`：`reasoningId`
- `reasoning.snapshot`：`reasoningId`, `runId`, `text`, `taskId?`
- `content.start`：`contentId`, `runId`, `taskId?`
- `content.delta`：`contentId`, `delta`
- `content.end`：`contentId`
- `content.snapshot`：`contentId`, `runId`, `text`, `taskId?`

### 工具、产物与动作事件

- `tool.start`：`toolId`, `runId`, `taskId?`, `toolName?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`
- `tool.args`：`toolId`, `delta`, `chunkIndex?`（字段名保持 `delta`，不使用 `args`）
- `tool.end`：`toolId`
- `tool.result`：`toolId`, `result`
- `artifact.publish`：`artifactId`, `chatId`, `runId`, `artifact`（独立事件；`artifact` 仅含 `type/name/mimeType/sizeBytes/url/sha256`；一次 `_artifact_publish_` 调用里有几个产物，就会发几条 `artifact.publish`）
- `tool.snapshot`：`toolId`, `runId`, `toolName?`, `taskId?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`, `arguments?`
- `action.start`：`actionId`, `runId`, `taskId?`, `actionName?`, `description?`
- `action.args`：`actionId`, `delta`
- `action.end`：`actionId`
- `action.param`：`actionId`, `param`
- `action.result`：`actionId`, `result`
- `action.snapshot`：`actionId`, `runId`, `actionName?`, `taskId?`, `description?`, `arguments?`

### 补充行为约束

- 无活跃 task 出错时：只发 `run.error`（不补 `task.fail`）。
- plain 模式（当前无 plan）不应出现 `task.*`，叶子事件直接归属 `run`。
- `GET /api/chat` 历史事件需与新规则对齐；历史使用 `*.snapshot` 替代 `start/end/delta/args` 细粒度流事件，并保留 `tool.result` / `action.result`；`plan.update` / `artifact.publish` 提升为顶层状态 `data.plan` / `data.artifact`，其中 `data.artifact.items[]` 为聚合视图。
- 历史里每个 run 都保留一个终态事件：成功为 `run.complete`，失败为 `run.error`，取消为 `run.cancel`；`chat.start` 仅首次一次。
- `/api/query` 在流式输出结束时追加传输层终止帧 `data:[DONE]`；该 sentinel 不属于 Event Model v2 业务事件，也不写入 chat 历史事件。
- `RenderQueue` 只影响传输 flush 行为，不改变上述业务事件类型与字段契约。
- `_artifact_publish_` 是隐藏的内置后端工具：接收 `artifacts[]`，每项为 `path/name?/description?`；成功时 `tool.result` 可返回 `{ok,artifacts:[{artifactId,artifact},...]}`。如果一次调用里有多个产物，实时流中就会出现多条 `artifact.publish`。

## Chat Storage V3.1

- 存储文件：`chats/{chatId}.jsonl`，JSONL 格式，**一行一个 step**，逐步增量写入。
- 行类型通过 `_type` 字段区分：
  - `"query"`：用户原始请求行。必带 `chatId`、`runId`、`updatedAt`、`query`。
  - `"step"`：一个执行步骤行。必带 `chatId`、`runId`、`_stage`、`_seq`、`updatedAt`、`messages`；可选 `taskId`、`system`、`plan`、`artifacts`。
- `_stage` 标识步骤阶段：`"oneshot"` / `"react"` / `"plan"` / `"execute"` / `"summary"`。
- `_seq` 全局递增序号，标识 run 内的步骤顺序。
- `query` 保存完整 query 结构（`requestId/chatId/agentKey/role/message/references/params/scene/stream`）。
- `system` 快照规则：每个 run 的第一个 step 写入；stage 切换且 system 变化时再写入；后续 step 如果 system 未变化则省略。
- `messages` 采用 OpenAI 风格：
  - `role=user`：`content[]`（text parts）+ `ts`
  - `role=assistant`：三种快照形态之一：`content[]` / `reasoning_content[]` / `tool_calls[]`
  - `role=tool`：`name` + `tool_call_id` + `content[]` + `ts`
- assistant / tool 扩展字段支持：`_reasoningId`、`_contentId`、`_msgId`、`_toolId`、`_actionId`、`_timing`、`_usage`。
- action / tool 判定：通过 `chat.storage.action-tools` 白名单；命中写 `_actionId`，否则写 `_toolId`。
- chat storage 回放约束：`reasoning_content` **不回传**给下一轮模型上下文。
- 滑动窗口：`k=20` 单位仍然是 **run**；`trimToWindow` 按 `runId` 分组，保留最近 `k` 个 run 的所有行。
- `_msgId`（格式 `m_xxxxxxxx`，8 位 hex）标识同一 LLM 响应拆分的多条 assistant 消息；同一模型回复中的 reasoning、content、tool_calls 消息共享相同 `_msgId`；tool result 到来后，下一个 reasoning / content delta 会重新生成 `_msgId`。
- `tool_calls` 拆分规则：每条 `role=assistant` 的 `tool_calls` 数组只含 **1 个**工具调用；并行多工具调用拆分为多条 assistant 消息，通过共享 `_msgId` 关联。
- `_toolId` 和 `_actionId` 写入 `StoredMessage` 外层（与 `_reasoningId`、`_contentId` 同级）；`StoredToolCall` 内层的 `_toolId` / `_actionId` 仅用于反序列化旧数据，新数据不再写入；读取时先查外层，再 fallback 内层。

**_toolId 生成规则：**

| 工具类型 | 生成规则 |
|----------|---------|
| backend（`type=function`） | 直接使用 LLM 原始 `tool_call_id`（如 `call_b7332997a5b1490ca7195293`） |
| frontend（`type=frontend`） | `t_` + 8 位 hex（系统生成） |
| action（`type=action`） | `a_` + 8 位 hex（系统生成） |

**ID 前缀：**

| ID 类型 | 前缀 |
|---------|------|
| reasoningId | `r_` |
| contentId | `c_` |
| toolId (frontend) | `t_` |
| actionId | `a_` |
| msgId | `m_` |

SSE 事件中的 reasoningId / contentId 格式：`{runId}_r_{seq}` / `{runId}_c_{seq}`。

**_usage：**

- 通过 `stream_options.include_usage=true` 请求 LLM provider 返回真实 usage 数据。
- usage 通过管道穿透：`LlmDelta` → `AgentDelta` → `StepAccumulator.capturedUsage` → `RunMessage` → `StoredMessage._usage`。
- 当 LLM 未返回 usage 时 `_usage` 使用默认占位结构。

## Configuration

主配置事实源：`src/main/resources/application.yml`。结构化覆盖配置来自 `configs/`，通过启动期扫描加载。

关键目录变量：`AGENTS_DIR`（`runtime/agents`）、`CHATS_DIR`（`runtime/chats`）、`REGISTRIES_DIR`（`runtime/registries`，含 `providers/models/mcp-servers/viewport-servers` 子目录）、`OWNER_DIR`（`runtime/owner`）、`SKILLS_MARKET_DIR`（`runtime/skills-market`）、`SCHEDULES_DIR`（`runtime/schedules`）。

完整环境变量表、Provider 配置、挂载目录映射表与迁移说明详见 [docs/configuration-reference.md](./docs/configuration-reference.md)。

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

- 2026-04-05：CLAUDE.md 二次调整 — 还原 SSE 事件契约、Chat Storage V3.1 完整规范、REST API 契约至 CLAUDE.md；删除 `docs/sse-event-contract.md`、`docs/chat-storage-spec.md`、`docs/sandbox-config-reference.md`；sandboxConfig YAML 配置拆入 `docs/agent-definition-reference.md`，挂载目录映射表拆入 `docs/configuration-reference.md`，`docs/configuration-reference.md` 精简掉内部参数。
- 2026-04-05：CLAUDE.md 瘦身重构，将 Agent Definition 参考移至 `docs/`。
- 2026-04-05：完成目录清理与命名对齐。`ChatMessage` 迁移到 `chat/storage/`，`ArtifactEventPayload` 迁移到 `chat/event/`，并同步更新引用。
- 2026-04-05：移除 `AgentDefinitionLoader` 中 `ONESHOT` / `REACT` 对顶层 `promptFile` 的 legacy 回退；目录化 prompt 契约收敛到 stage 级字段。
- 2026-04-05：移除 schedule 顶层 `zoneId` / `params` 与 provider 顶层 `model` 的过渡兼容拦截；`ConfigDirectoryEnvironmentPostProcessor` 的启动期 deprecated key 守卫保持不变。
