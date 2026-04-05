# Configuration 参考

主配置事实源：`src/main/resources/application.yml`。结构化覆盖配置来自 `configs/`，通过启动期扫描加载。

## Spring / Server

| 项 | 默认值 | 说明 |
|----|--------|------|
| `server.port` | `8080` | HTTP 端口（环境变量 `SERVER_PORT`） |
| `spring.application.name` | `agent-platform-runner` | 服务名 |
| `CONFIGS_DIR` | `./configs` 或 `/opt/configs` | 结构化配置目录；显式设置时优先生效 |

## 核心环境变量

### 目录配置

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENTS_DIR` | `agent.agents.external-dir` | `runtime/agents` | Agent Definition 目录 |
| `TEAMS_DIR` | `agent.teams.external-dir` | `runtime/teams` | Team 定义目录 |
| `OWNER_DIR` | `agent.owner.external-dir` | `runtime/owner` | Owner 目录，`owner` tag 从此目录读取 Markdown 文件 |
| `REGISTRIES_DIR` | `agent.providers.external-dir` / `agent.models.external-dir` / `agent.mcp-servers.registry.external-dir` / `agent.viewport-servers.registry.external-dir` | `runtime/registries` | 动态 registry 根目录；子目录固定为 `providers`、`models`、`mcp-servers`、`viewport-servers` |
| `ROOT_DIR` | `agent.root.external-dir` | `runtime/root` | 容器 `/root` 对应的 runner 根目录 |
| `PAN_DIR` | `agent.pan.external-dir` | `runtime/pan` | 容器 `/pan` 对应的 runner 目录 |
| `SKILLS_MARKET_DIR` | `agent.skills.external-dir` | `runtime/skills-market` | 技能目录 |
| `SCHEDULES_DIR` | `agent.schedule.external-dir` | `runtime/schedules` | 计划任务目录 |
| `CHATS_DIR` | `chat.storage.dir` | `runtime/chats` | 聊天存储目录 |

### H2A / Render

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_H2A_RENDER_FLUSH_INTERVAL_MS` | `agent.h2a.render.flush-interval-ms` | `0` | H2A RenderQueue 时间窗口刷新 |
| `AGENT_H2A_RENDER_MAX_BUFFERED_CHARS` | `agent.h2a.render.max-buffered-chars` | `0` | H2A RenderQueue 字符阈值刷新 |
| `AGENT_H2A_RENDER_MAX_BUFFERED_EVENTS` | `agent.h2a.render.max-buffered-events` | `0` | H2A RenderQueue 事件数阈值刷新 |

### Bash 工具

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_BASH_WORKING_DIRECTORY` | `agent.tools.bash.working-directory` | 项目运行根目录 | Bash 工具工作目录 |
| `AGENT_BASH_ALLOWED_PATHS` | `agent.tools.bash.allowed-paths` | （空） | Bash 工具路径白名单（逗号分隔） |
| `AGENT_BASH_ALLOWED_COMMANDS` | `agent.tools.bash.allowed-commands` | （空 = 拒绝执行） | Bash 允许命令列表（逗号分隔） |
| `AGENT_BASH_PATH_CHECKED_COMMANDS` | `agent.tools.bash.path-checked-commands` | （空 = 默认等于 allowed-commands） | 启用路径校验的命令列表 |
| `AGENT_BASH_PATH_CHECK_BYPASS_COMMANDS` | `agent.tools.bash.path-check-bypass-commands` | （空 = 默认关闭） | 跳过路径校验的命令列表 |
| `AGENT_BASH_SHELL_FEATURES_ENABLED` | `agent.tools.bash.shell-features-enabled` | `false` | Bash 高级 shell 语法开关 |
| `AGENT_BASH_SHELL_TIMEOUT_MS` | `agent.tools.bash.shell-timeout-ms` | `10000` | Shell 模式超时（ms） |

### Container Hub

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_CONTAINER_HUB_ENABLED` | `agent.tools.container-hub.enabled` | `false` | 是否启用 Container Hub 沙箱 |
| `AGENT_CONTAINER_HUB_BASE_URL` | `agent.tools.container-hub.base-url` | `http://127.0.0.1:8080` | Container Hub 服务地址 |
| `AGENT_CONTAINER_HUB_AUTH_TOKEN` | `agent.tools.container-hub.auth-token` | （空） | Container Hub Bearer Token |
| `AGENT_CONTAINER_HUB_DEFAULT_ENVIRONMENT_ID` | `agent.tools.container-hub.default-environment-id` | （空） | 默认环境 ID |
| `AGENT_CONTAINER_HUB_DEFAULT_SANDBOX_LEVEL` | `agent.tools.container-hub.default-sandbox-level` | `run` | 全局默认沙箱级别 |

### 挂载目录映射

默认仅挂载 5 个真实容器路径，其余平台目录需通过 `sandboxConfig.extraMounts` 显式声明。

| 容器内路径 | RUN 宿主路径 | AGENT / GLOBAL 宿主路径 | 默认策略 | 默认模式 | 配置键（为空时 fallback） |
|-----------|-------------|-------------------------|----------|----------|--------------------------|
| `/workspace` | `{chatDataDir}/{chatId}` | `{chatDataDir}` | 默认挂载 | `rw` | `chat.storage.dir` |
| `/root` | `{rootDir}` | `{rootDir}` | 默认挂载 | `rw` | `agent.root.external-dir` |
| `/skills` | `{agentsDir}/{agentKey}/skills`（若存在，否则 `{skillsDir}`） | `{agentsDir}/{agentKey}/skills`（若存在，否则 `{skillsDir}`） | 默认挂载 | `ro` | `agent.skills.external-dir` |
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
| `/chats` | `{chatDataDir}` | `{chatDataDir}` | `extraMounts` 按需 | 显式 `mode` | `chat.storage.dir` |
| `/skills-market` | `{skillsDir}` | `{skillsDir}` | `extraMounts` 按需 | 显式 `mode` | `agent.skills.external-dir` |
| `/owner` | `{agentsDir}/../owner` | `{agentsDir}/../owner` | `extraMounts` 按需 | 显式 `mode` | `agent.agents.external-dir` 的父目录 |

### Auth

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_AUTH_ENABLED` | `agent.auth.enabled` | `true` | JWT 认证开关 |
| `AGENT_AUTH_JWKS_URI` | `agent.auth.jwks-uri` | （空） | JWKS 地址 |
| `AGENT_AUTH_ISSUER` | `agent.auth.issuer` | （空） | JWT issuer |
| `CHAT_IMAGE_TOKEN_SECRET` | `agent.chat-image-token.secret` | （空） | 图片令牌签名密钥（为空则 token 机制禁用） |
| `CHAT_RESOURCE_TICKET_ENABLED` | `agent.chat-image-token.resource-ticket-enabled` | `true` | `/api/resource` 的 `t` resource ticket 开关（关闭后忽略 `t`） |

说明：`agent.auth.local-public-key` 仍支持 YAML 内嵌 PEM；推荐改用 `AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE` 或 `agent.auth.local-public-key-file` 指向独立 PEM 文件。

### Frontend / Schedule / Skills / MCP

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_TOOLS_FRONTEND_SUBMIT_TIMEOUT_MS` | `agent.tools.frontend.submit-timeout-ms` | `300000` | 前端工具提交等待超时（ms） |
| `AGENT_SCHEDULE_ENABLED` | `agent.schedule.enabled` | `true` | 计划任务总开关 |
| `AGENT_SCHEDULE_DEFAULT_ZONE_ID` | `agent.schedule.default-zone-id` | 系统时区 | 计划任务默认时区 |
| `AGENT_SCHEDULE_POOL_SIZE` | `agent.schedule.pool-size` | `4` | 计划任务线程池大小 |
| `AGENT_SKILLS_MAX_PROMPT_CHARS` | `agent.skills.max-prompt-chars` | `8000` | 技能 prompt 最大字符数 |
| `AGENT_MCP_SERVERS_ENABLED` | `agent.mcp-servers.enabled` | `true` | MCP server 总开关 |

### Memory

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `AGENT_MEMORY_CONTEXT_TOP_N` | `agent.memory.context-top-n` | `5` | `memory` tag 默认注入条数 |
| `AGENT_MEMORY_CONTEXT_MAX_CHARS` | `agent.memory.context-max-chars` | `4000` | `memory` tag 最大字符数 |
| `MEMORY_DIR` | `agent.memory.storage.dir` | `runtime/memory` | 中央记忆存储根目录 |
| `AGENT_MEMORY_AUTO_REMEMBER_ENABLED` | `agent.memory.auto-remember.enabled` | `false` | 成功 run 后是否自动触发 remember 抽取 |
| `AGENT_MEMORY_REMEMBER_MODEL_KEY` | `agent.memory.remember.model-key` | （空） | remember 使用的模型 key |
| `AGENT_MEMORY_REMEMBER_TIMEOUT_MS` | `agent.memory.remember.timeout-ms` | `60000` | remember LLM 调用超时（ms） |
| `AGENT_MEMORY_EMBEDDING_PROVIDER_KEY` | `agent.memory.embedding-provider-key` | （空） | embedding provider key |
| `AGENT_MEMORY_EMBEDDING_MODEL` | `agent.memory.embedding-model` | （空） | embedding model |

### Logging

| 环境变量 | 属性键 | 默认值 | 说明 |
|---------|--------|-------|------|
| `LOGGING_AGENT_LLM_INTERACTION_ENABLED` | `logging.agent.llm.interaction.enabled` | `true` | LLM 交互日志开关 |
| `LOGGING_AGENT_LLM_INTERACTION_MASK_SENSITIVE` | `logging.agent.llm.interaction.mask-sensitive` | `true` | 日志脱敏开关 |

## Provider 配置

通常在 `runtime/registries/providers/<provider>.yml`，外置共享目录时也保持 `registries/providers/<provider>.yml`。

`agent.providers.<providerKey>` 支持：

- `base-url`
- `api-key`
- `defaultModel`（可选，作为 provider 默认 model）
- `protocols.<PROTOCOL>.endpoint-path`（可选，按线协议覆盖请求 endpoint 路径）

说明：

- provider 不再绑定 protocol；协议由 `registries/models/*.yml` 中 `protocol` 字段决定。
- `OPENAI` 未显式配置 `protocols.OPENAI.endpoint-path` 时，会按 `base-url` 推导默认 completions 路径。
- provider 只负责连接信息与 endpoint 组织，不负责声明 Agent 使用哪个协议。

## 迁移说明（Breaking Change）

- 旧键已禁用：`agent.catalog.*`、`agent.viewport.*`、`agent.capability.*`、`agent.skill.*`、`agent.team.*`、`agent.model.*`、`agent.mcp.*`、`memory.chat.*`、`memory.chats.*`。
- 旧环境变量已禁用：`AGENT_CONFIG_DIR`、`AGENT_AGENTS_EXTERNAL_DIR`、`AGENT_TEAMS_EXTERNAL_DIR`、`AGENT_MODELS_EXTERNAL_DIR`、`AGENT_PROVIDERS_EXTERNAL_DIR`、`AGENT_TOOLS_EXTERNAL_DIR`、`AGENT_SKILLS_EXTERNAL_DIR`、`AGENT_VIEWPORTS_EXTERNAL_DIR`、`AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR`、`AGENT_VIEWPORT_SERVERS_REGISTRY_EXTERNAL_DIR`、`AGENT_SCHEDULE_EXTERNAL_DIR`、`AGENT_DATA_EXTERNAL_DIR`、`MEMORY_CHATS_K`、`MEMORY_CHATS_CHARSET`、`MEMORY_CHATS_ACTION_TOOLS`、`MEMORY_CHATS_INDEX_SQLITE_FILE`、`MEMORY_CHATS_INDEX_AUTO_REBUILD_ON_INCOMPATIBLE_SCHEMA` 等。
- `CHATS_DIR` 保留不变。
- Memory 兼容别名已删除：仅保留 `MEMORY_DIR`、`AGENT_MEMORY_AUTO_REMEMBER_ENABLED`、`AGENT_MEMORY_REMEMBER_MODEL_KEY`、`AGENT_MEMORY_REMEMBER_TIMEOUT_MS`；`AGENT_MEMORY_STORAGE_DIR` 已移除。
- 当前文档仅记录 `application.yml` 中实际使用的键；历史目录类兼容变量不再作为公开 contract。
