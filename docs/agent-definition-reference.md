# Agent Definition 参考

## 完整 Schema

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
  backends: ["_bash_", "_datetime_"]
  frontends: ["show_weather_card"]
  actions: ["switch_theme"]
skillConfig:
  skills: ["docx", "screenshot"]
contextConfig:
  tags: ["system", "context", "owner", "auth", "memory"]
memoryConfig:
  enabled: false
mode: ONESHOT
toolChoice: AUTO
budget:
  runTimeoutMs: 300000
  model:
    maxCalls: 30
    timeoutMs: 120000
    retryCount: 0
  tool:
    maxCalls: 50
    timeoutMs: 300000
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
  maxSteps: 60
planExecute:
  plan:
    systemPrompt: 规划提示词
    deepThinking: true
  execute:
    systemPrompt: 执行提示词
  summary:
    systemPrompt: 总结提示词
  maxSteps: 60
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
  backends: ["_bash_", "_datetime_"]
  frontends: ["show_weather_card"]
  actions: ["switch_theme"]
mode: ONESHOT
plain:
  systemPrompt: |
    系统提示词
    支持多行
```

## sandboxConfig 配置

`sandboxConfig` 用于声明 Agent 的 Container Hub 沙箱参数，包括 `environmentId`、`level` 和 `extraMounts`。

### sandboxConfig 完整示例

```yaml
sandboxConfig:
  environmentId: shell
  level: agent        # run / agent / global；为空时使用全局 default-sandbox-level
  extraMounts:
    - platform: models
      mode: ro
    - platform: tools
      mode: rw
    - platform: skills-market
      mode: ro
    - platform: chats
      mode: ro
    - platform: owner
      mode: rw
    - source: /abs/host/path
      destination: /datasets
      mode: ro
    - destination: /skills
      mode: rw
```

### extraMounts 平台简写

| `platform` | 容器路径 |
|-----------|----------|
| `models` | `/models` |
| `tools` | `/tools` |
| `agents` | `/agents` |
| `viewports` | `/viewports` |
| `viewport-servers` | `/viewport-servers` |
| `teams` | `/teams` |
| `schedules` | `/schedules` |
| `mcp-servers` | `/mcp-servers` |
| `providers` | `/providers` |
| `chats` | `/chats` |
| `skills-market` | `/skills-market` |
| `owner` | `/owner` |

### 挂载原则

- 默认最小集：默认只挂载 `/workspace`、`/root`、`/skills`、`/pan`、`/agent`，不再默认暴露全量平台配置目录。
- agent 就近原则：当前 agent 若采用目录化布局，默认挂载其自身目录到 `/agent`；扁平 YAML agent 不强制创建该挂载。
- 本地优先原则：目录化 agent 在 `RUN/AGENT` 级别会优先把 `agents/<agentKey>/skills` 挂到 `/skills`；没有本地 skills 时才回落到共享 market。
- 默认安全模式：`/skills` 与 `/agent` 默认只读；`/workspace`、`/root`、`/pan` 默认读写。
- 按需显式原则：`/models`、`/tools`、`/agents`、`/viewports`、`/teams`、`/schedules`、`/mcp-servers`、`/providers`、`/chats`、`/skills-market`、`/owner` 仅能通过 `sandboxConfig.extraMounts` 显式恢复。
- 模式显式原则：所有按需平台挂载和自定义挂载都必须显式声明 `mode: ro|rw`。
- 基础挂载覆盖原则：若只想修改 `/workspace`、`/root`、`/skills`、`/pan`、`/agent` 的模式，可在 `extraMounts` 中只写 `destination + mode` 覆盖默认模式，不新增第二个挂载。
- 最小暴露原则：agent 只应声明完成任务所必需的额外挂载，避免把无关目录带入沙箱。
- 安全优先原则：custom mount 必须满足"源目录存在、目标路径为绝对路径、目标路径不冲突"；不满足时直接 fail-fast。
- 敏感目录显式授权：`providers` 属于敏感挂载，即使在 `extraMounts` 中声明，也必须先有全局 `agent.providers.external-dir` 目录。

### 约束规则

- `platform` 未知时仅 warn 并跳过。
- `platform` 挂载必须显式声明 `mode: ro|rw`。
- custom mount 必须提供 `source + destination + mode`。
- `platform: skills-market` 绑定的是共享 skill market，容器内路径固定为 `/skills-market`。
- `platform: owner` 绑定的是 owner 目录；该目录内的正式 owner 文档位于 `owner/OWNER.md`。
- 默认基础挂载 `/workspace`、`/root`、`/skills`、`/pan`、`/agent` 可通过 `destination + mode` 覆盖默认模式。
- custom mount 的 `destination` 必须是绝对路径，且不能与已有挂载冲突。
- custom mount 的 `source` 必须是已存在目录。
- `platform: providers` 只有在 `mounts.providers-dir` 已显式配置时才可用。

## 模式配置块

各模式对应配置块（至少需要一个）：

- `ONESHOT` → `plain.systemPrompt`
- `REACT` → `react.systemPrompt`
- `PLAN_EXECUTE` → `planExecute.plan.systemPrompt` + `planExecute.execute.systemPrompt`

## 配置规则

**modelConfig 继承：**

- 支持外层默认 + stage 内层覆盖；内层优先。
- 外层 `modelConfig` 可省略，但"外层或任一 stage"至少要有一处 `modelConfig.modelKey`。
- `provider/modelId/protocol` 不在 Agent Definition 文件中声明，统一由 `registries/models/<modelKey>.yml` 解析得到。

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
    - memory
```

支持 7 个 tag，不支持的 tag 在加载时被静默过滤。tag 名大小写不敏感，内部统一转为小写。tag 定义位于 `RuntimeContextTags.java`，运行时内容由 `RuntimeContextPromptService.buildPrompt()` 按声明顺序依次生成。

**重要：tag 的存在与否与 `.env` 目录配置、Docker 容器挂载无关。** 例如，`owner` tag 不依赖 Container Hub 的 `/owner` 平台挂载是否配置；`context` tag 不依赖沙箱目录是否挂载。tag 读取的是 runner 进程本地可见的文件系统路径，Container Hub 挂载解析的是发给 Container Hub 的宿主机路径，两者通过不同管道消费同一组 `.env` 变量。

**memoryConfig 配置：**

```yaml
memoryConfig:
  enabled: true
```

- `memoryConfig.enabled` 默认 `false`
- 开启后运行时会自动附带 `_memory_write_`、`_memory_read_`、`_memory_search_`
- 当 `agent.memory.auto-remember.enabled=false` 时，成功 run 结束后自动沉淀 1 条 `run-summary` memory
- 当 `agent.memory.auto-remember.enabled=true` 时，成功 run 结束后改为走 remember 抽取链路
- `memoryConfig.enabled` 与 `contextConfig.tags: [memory]` 完全独立，前者不决定上下文注入，后者不决定自动记忆

## tag 详细说明

**`system`** — 注入系统环境信息（`RuntimeContextPromptService.buildSystemEnvironmentSection()`）
- 内容：OS 名称与版本、CPU 架构、Java 版本、时区、locale、当前日期、当前日期时间、runner 工作目录
- 数据来源：`System.getProperty()`、`ZonedDateTime.now()`、`Locale.getDefault()`
- 无外部依赖，始终可用

**`context`** — 注入运行目录与当前会话上下文（`RuntimeContextPromptService.buildContextSection()`）
- 工作区路径（来自 Spring 属性）：`runtime_home`、`root_dir`、`agents_dir`、`chats_dir`、`data_dir`、`skills_market_dir`、`schedules_dir`、`owner_dir`、`chat_attachments_dir`
- 请求上下文（来自 `AgentRequest` / `RuntimeRequestContext`）：`chatId`、`requestId`、`runId`、`agentKey`、`teamId`、`role`、`chatName`、`scene`、`references`
- 路径解析：通过 `resolveWorkspacePaths()` 从 Spring `Environment` 读取 `agent.agents.external-dir` 等属性，Docker 模式下显示容器内路径（`/opt/agents` 等），本地模式下显示真实路径

**`owner`** — 注入 owner 目录下所有 Markdown 文件内容（`RuntimeContextPromptService.buildOwnerSection()`）
- 递归遍历 owner 目录下所有 `.md`/`.markdown` 文件，按相对路径字母序排列
- 每个文件以 `--- file: <相对路径>` 分隔，完整读取文件内容（无截断）
- 不可读的文件输出 `[UNREADABLE: <相对路径>]`
- owner 目录解析：通过 `OwnerProperties.getExternalDir()`（Spring 属性 `agent.owner.external-dir`），与其他目录路径解析方式一致
- 目录不存在或无 Markdown 文件时返回空串，不报错

**`auth`** — 注入 JWT 主体信息（`RuntimeContextPromptService.buildAuthIdentitySection()`）
- 内容：`subject`、`deviceId`、`scope`、`issuedAt`、`expiresAt`
- 数据来源：请求头中的 JWT token，由 `JwksJwtVerifier` 解析为 `JwtPrincipal`
- 未认证（principal 为 null）时返回空串

**`sandbox`** — 注入沙箱环境信息与 environment prompt（`SandboxContextResolver.resolve()`）
- 元数据：`environmentId`（实际生效）、`configuredEnvironmentId`（agent 配置）、`defaultEnvironmentId`（全局默认）、`level`、`container_hub_enabled`、`uses_sandbox_bash`、`extraMounts`
- environment prompt：从 Container Hub `GET /api/environments/{environmentId}/agent-prompt` 拉取原文
- **强依赖**：非 `shell` 环境中，environment prompt 缺失、为空或请求失败时抛 `IllegalStateException`，请求直接失败
- `shell` 环境允许 prompt 缺失或为空（日志记录但不报错）
- Container Hub 不可用（`containerHubClient` 为 null）时直接失败

**`all-agents`** — 注入全部已注册 agent 的摘要（`AgentQueryService.buildAllAgentDigests()` + `RuntimeContextPromptService.buildAllAgentsSection()`）
- 字段：`key/name/role/description/mode/modelKey/tools/skills/sandbox(environmentId+level)`
- 格式：YAML 风格文本块，agent 间以 `---` 分隔
- 截断：总字符数超过 12,000 时停止添加，附加 `[TRUNCATED: all-agents exceeds max chars=12000, included=N/M]`
- 按 agent key 字母序排列

**`memory`** — 注入 agent SQLite memory 摘要（`RuntimeContextPromptService.buildAgentMemorySection()` + `AgentMemoryStore`）
- 有 `request.message()` 时按语义相关性取 `contextTopN`
- 无 `request.message()` 时按 `importance desc` 取 `contextTopN`
- 格式：`Runtime Context: Agent Memory`，每条包含 `id/category/importance/tags/content`
- 总字符数超过 `agent.memory.context-max-chars` 时截断，并附带 `[TRUNCATED: agent-memory exceeds max chars=...]`
- 仅控制"是否把已存储 memory 摘要注入运行时上下文"，不控制自动记忆或 memory tools 暴露
- memory 功能关闭或无数据时返回空串，不影响 agent 运行

## tag 条件解析

`sandbox`、`all-agents` 和 `memory` 仅在 agent 声明了对应 tag 时才解析（通过 `requiresContextTag()` 判断，避免不必要的 HTTP 调用和注册表遍历）。`workspacePaths`（供 `system`、`context`、`owner` 使用）在每次请求中无条件构建。

## tag 内容与路径来源关系

| Tag | 路径/数据来源 | 与 `.env` `*_DIR` 的关系 |
|-----|-------------|------------------------|
| `system` | `System.getProperty()` | 无关 |
| `context` | Spring 属性（`agent.*.external-dir`） | 间接：本地模式通过 `${*_DIR}` 占位符读取；Docker 模式被 `application-docker.yml` 字面量覆盖 |
| `owner` | `OwnerProperties.getExternalDir()`（Spring 属性 `agent.owner.external-dir`） | 间接：与其他目录一致，本地通过 `${OWNER_DIR}` 占位符读取；Docker 模式被 `application-docker.yml` 覆盖为 `/opt/owner` |
| `auth` | JWT token claims | 无关 |
| `sandbox` | Container Hub HTTP API | 无关（读的是远端服务） |
| `all-agents` | `AgentRegistry` 内存注册表 | 无关 |
| `memory` | `AgentMemoryStore`（agent 专属 SQLite） | 间接：受 `agent.memory.*` 与 `agent.agents.external-dir` 影响 |

## 关键实现文件

| 文件 | 职责 |
|------|------|
| `agent/RuntimeContextTags.java` | tag 常量定义、normalize/validate |
| `agent/RuntimeContextPromptService.java` | 各 tag 内容生成、`resolveWorkspacePaths()`、`resolveOwnerDir()` |
| `service/memory/AgentMemoryStore.java` | agent 记忆 SQLite/FTS5/向量混合检索 |
| `service/memory/AgentMemoryService.java` | central journal markdown 写入、memory 根目录与 journal 路径解析 |
| `service/memory/GlobalMemoryRequestService.java` | LLM 驱动的 remember 抽取主逻辑，负责加载 chat、构造 prompt、解析 JSON 并写入 central memory/journal |
| `service/memory/RememberCaptureException.java` | remember 流程异常，统一由 `ApiExceptionHandler` 映射为 HTTP `500` |
| `chat/storage/ChatMessage.java` | chat 历史回放与 LLM 上下文共享的会话消息 sealed interface |
| `chat/storage/StoredMessageConverter.java` | Chat Storage JSONL 与 `ChatMessage` 之间的转换与归一化 |
| `chat/storage/ChatStorageStore.java` | chat 历史窗口读取、JSONL 增量落盘与回放入口 |
| `chat/event/ArtifactEventPayload.java` | `artifact.publish` 事件载荷 record |
| `chat/event/ArtifactPublishService.java` | artifact 文件落盘、校验与发布主逻辑 |
| `agent/runtime/sandbox/SandboxContextResolver.java` | sandbox tag 专用：environment prompt 拉取与校验 |
| `controller/MemoryController.java` | `/api/remember`、`/api/learn` 入口，负责 request logging 摘要与返回封装 |
| `config/properties/AgentMemoryProperties.java` | `agent.memory.*` 聚合配置绑定，包含 `storage.*` 与 `remember.*` 子配置 |
| `model/RuntimeRequestContext.java` | 数据载体：`WorkspacePaths`、`SandboxContext`、`AgentDigest` |
| `service/AgentQueryService.java` | 组装 `RuntimeRequestContext`（`buildRuntimeRequestContext()`）、条件解析 |
| `agent/AgentDefinitionLoader.java` | 加载 agent 定义时收集 `contextTags`（`collectContextTags()`） |

## 文件格式与优先级

- `agents/` 支持扁平 YAML（`<key>.yml/.yaml`）与目录化 Agent（`<key>/agent.yml`）；推荐目录化布局。
- 目录中若出现旧 `*.json` 会直接 fail-fast。
- 同 basename 冲突时优先 `.yml`，并忽略对应 `.yaml`。
- 前 4 行固定为 `key`、`name`、`role`、`description`，且必须从第 1 行开始、禁止前置空行 / 注释、必须是单行 inline value。
- `ONESHOT` 只读取 `plain.promptFile`；`REACT` 只读取 `react.promptFile`；`PLAN_EXECUTE` 读取 `planExecute.plan|execute|summary.promptFile`。
- 顶层 `promptFile` 不再作为 `ONESHOT` / `REACT` 的目录化 prompt 来源。
- 目录化 Agent 可附带可选文件：`SOUL.md`、`AGENTS.md`、`AGENTS.plain.md`、`AGENTS.react.md`、`AGENTS.plan.md`、`AGENTS.execute.md`、`AGENTS.summary.md`、`memory/memory.md`。
- 运行时 system prompt 合并顺序：`SOUL.md` → Runtime Context（按 `contextConfig.tags` 注入）→ `AGENTS.md` / stage 专属 `AGENTS.<stage>.md` → `memory/memory.md` → YAML stage `systemPrompt` → skills appendix → tool appendix。

**多行 Prompt 写法：**

- YAML 推荐使用 block scalar（`|` / `>`）。

**步骤上限：**

- `react.maxSteps` 控制 REACT 循环上限。
- `planExecute.maxSteps` 控制 PLAN_EXECUTE 执行阶段步骤上限。
- 根目录 `.env` / 环境变量可设置全局默认 budget 与默认 steps；agent.yml 中显式声明的 `budget` 和 `maxSteps` 优先于全局默认。
