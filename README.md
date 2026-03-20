# springai-agent-platform

本仓库是可独立构建和部署的 Spring AI Agent 服务，已将流式事件模块源码内置到本仓库，不依赖外置 jar。

> 详细架构设计、数据模型、API 契约和开发约束见 [CLAUDE.md](./CLAUDE.md)。

## 提供接口

- `GET /api/agents`: 智能体列表
- `GET /api/teams`: 团队列表（team 元数据，含成员 `agentKeys`）
- `GET /api/skills`: 技能列表（支持 `tag` 过滤）
- `GET /api/tools`: 工具列表（支持 `tag`、`kind=backend|frontend|action` 过滤）
- `GET /api/tool?toolName=...`: 单个工具详情
- `GET /api/chats`: 会话列表（支持 `lastRunId` 增量查询、`agentKey` 过滤）
- `POST /api/read`: 标记单个会话已读
- `GET /api/chat?chatId=...`: 会话详情（默认返回快照事件流）
- `GET /api/chat?chatId=...&includeRawMessages=true`: 会话详情（附带原始 `rawMessages`）
- `GET /api/data?file={filename}&download=true|false`: 静态文件服务（图片 inline / 附件 download）
- `GET /api/viewport?viewportKey=...`: 获取工具/动作视图内容
- `POST /api/query`: 提问接口（默认返回标准 SSE；`requestId` 可省略，缺省时等于 `runId`）
- `POST /api/submit`: Human-in-the-loop 提交接口
- `POST /api/steer`: 运行中引导接口
- `POST /api/interrupt`: 运行中断接口

## 不兼容升级说明

- 元数据单项接口 `/api/agent`、`/api/skill` 已下线；`/api/tool` 仍保留用于查询单个工具详情。
- 会话索引使用 SQLite（默认文件：`chats.db`）。
- 聊天正文与回放仍使用 `./chats/{chatId}.json`，接口行为保持兼容。

## 返回格式约定

- `POST /api/query` 返回 SSE event stream。
- `/api/query` 流结束时会追加传输层终止帧 `data:[DONE]`（不属于业务事件模型，也不会出现在 `/api/chat` 的历史 `events` 中）。
- 其它 JSON 接口统一返回：

```json
{
  "code": 0,
  "msg": "success",
  "data": {}
}
```

- `code = 0` 表示成功，`code > 0` 表示失败（整型错误码），`msg` 为错误信息，`data` 为返回数据。
- `data` 直接放业务内容，不再额外包同名字段，例如：
  - 智能体列表：`data` 直接是 `agents[]`
  - 团队列表：`data` 直接是 `teams[]`
  - 技能列表：`data` 直接是 `skills[]`
  - 工具列表：`data` 直接是 `tools[]`
  - 会话详情：`data` 直接是 `chat`
  - 视图详情：`data` 直接是视图内容（`html` 时为 `{ "html": "..." }`，`qlc` 时为 schema JSON）
- `GET /api/chat` 默认始终返回 `events`；仅当 `includeRawMessages=true` 时才返回 `rawMessages`。
- 事件协议仅支持 Event Model v2，不兼容旧命名（如 `query.message`、`message.start|delta|end`、`message.snapshot`）。

`GET /api/agents` 示例（`role` 为顶层字段）：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "key": "demoConfirmDialog",
      "name": "灵犀",
      "description": "内置示例：REACT 模式 + 确认对话框（human-in-the-loop）",
      "role": "确认对话示例",
      "meta": {
        "model": "qwen3-max",
        "mode": "REACT",
        "tools": ["datetime", "confirm_dialog"],
        "skills": []
      }
    }
  ]
}
```

`GET /api/teams` 返回结构：

- 列表项：`teamId`, `name`, `icon`, `agentKeys`, `meta.invalidAgentKeys`, `meta.defaultAgentKey`, `meta.defaultAgentKeyValid`

`GET /api/skills` 返回结构：

- 列表项：`key`, `name`, `description`, `meta.promptTruncated`

`GET /api/tools` 返回结构：

- 列表项：`key`, `name`, `label`, `description`, `meta.kind`, `meta.toolType`, `meta.viewportKey`, `meta.strict`

`GET /api/tool` 返回结构：

- 详情字段：`key`, `name`, `label`, `description`, `afterCallHint`, `parameters`, `meta.kind`, `meta.toolType`, `meta.viewportKey`, `meta.strict`

`GET /api/chats` 示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "chatId": "d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656",
      "chatName": "元素碳的简介，100",
      "agentKey": "demoModePlain",
      "teamId": null,
      "createdAt": 1770866044047,
      "updatedAt": 1770866412459,
      "lastRunId": "mtoewf3u",
      "lastRunContent": "碳是一种非金属元素...",
      "readStatus": 0,
      "readAt": null
    }
  ]
}
```

`GET /api/chats?lastRunId=mtoewf3u` 示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "chatId": "d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656",
      "chatName": "元素碳的简介，100",
      "agentKey": "demoModePlain",
      "teamId": null,
      "createdAt": 1770866044047,
      "updatedAt": 1770867412459,
      "lastRunId": "mtoewfr9",
      "lastRunContent": "碳在自然界中有多种同素异形体...",
      "readStatus": 0,
      "readAt": null
    }
  ]
}
```

`GET /api/chats?agentKey=demoModePlain&lastRunId=mtoewf3u` 示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "chatId": "d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656",
      "chatName": "元素碳的简介，100",
      "agentKey": "demoModePlain",
      "teamId": null,
      "createdAt": 1770866044047,
      "updatedAt": 1770867412459,
      "lastRunId": "mtoewfr9",
      "lastRunContent": "碳在自然界中有多种同素异形体...",
      "readStatus": 0,
      "readAt": null
    }
  ]
}
```

`POST /api/read` 示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "chatId": "d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656",
    "readStatus": 1,
    "readAt": 1770867421123
  }
}
```

`GET /api/chat` 的 `data` 结构如下：

```json
{
  "chatId": "8cdb2094-9dbf-47d1-a17f-bc989a236a5c",
  "chatName": "元素碳的简介，100",
  "events": [
    {
      "seq": 1,
      "type": "request.query",
      "requestId": "8ad0081d-191b-4990-9432-664ea0c38c3e",
      "chatId": "8cdb2094-9dbf-47d1-a17f-bc989a236a5c",
      "role": "user",
      "message": "元素碳的简介，100字",
      "timestamp": 1770863186548
    },
    {
      "seq": 5,
      "type": "content.snapshot",
      "contentId": "8ad0081d-191b-4990-9432-664ea0c38c3e_c_0",
      "text": "碳是一种非金属元素...",
      "timestamp": 1770863186549
    }
  ],
  "references": []
}
```

当 `includeRawMessages=true` 时，会额外返回：

```json
"rawMessages": [
  {
    "role": "user",
    "content": "元素碳的简介，100字",
    "ts": 1770863186548,
    "runId": "8ad0081d-191b-4990-9432-664ea0c38c3e"
  }
]
```

## 目录约定

```text
.
├── .dockerignore
├── .env.example
├── configs/
│   ├── *.example.yml
│   └── auth/
├── docker-compose.yml
├── example/
│   ├── agents/
│   ├── teams/
│   ├── models/
│   ├── mcp-servers/
│   ├── viewport-servers/
│   ├── viewports/
│   ├── tools/
│   ├── skills/
│   ├── schedules/
│   ├── providers/
│   ├── install-example-mac.sh
│   ├── install-example-linux.sh
│   └── install-example-windows.ps1
├── src/
├── providers/
├── data/
├── schedules/
├── nginx.conf
├── pom.xml
├── settings.xml
└── Dockerfile
```

## 构建与运行

### 流式模块说明

流式事件模块源码位于 `src/main/java/com/linlay/agentplatform/stream/**`，无需额外放置 jar。

### 本地运行

```bash
mvn clean test
make run
```

默认端口 `8080`（来自 `src/main/resources/application.yml` 的 `SERVER_PORT` 默认值）。
若 `.env` 或环境变量覆盖了端口（例如开发常用 `11949`），则以覆盖值为准。
注意：`make run` 会自动加载根目录 `.env`；直接执行 `mvn spring-boot:run` 则不会自动加载。

推荐把可配置运行时目录统一放到项目外，再通过 `.env` 指向它们。`configs/` 不是可配置目录，固定使用 runner 自带的 `./configs`（容器内固定挂载到 `/opt/configs`）。例如代码仓库保留在当前工作区，而运行目录放在共享目录：

```bash
AGENTS_DIR=/Users/you/runtime/runner/agents
TEAMS_DIR=/Users/you/runtime/runner/teams
MODELS_DIR=/Users/you/runtime/runner/models
PROVIDERS_DIR=/Users/you/runtime/runner/providers
MCP_SERVERS_DIR=/Users/you/runtime/runner/mcp-servers
VIEWPORT_SERVERS_DIR=/Users/you/runtime/runner/viewport-servers
SKILLS_MARKET_DIR=/Users/you/runtime/runner/skills-market
SCHEDULES_DIR=/Users/you/runtime/runner/schedules
CHATS_DIR=/Users/you/runtime/runner/chats
ROOT_DIR=/Users/you/runtime/runner/root
PAN_DIR=/Users/you/runtime/runner/pan
```

### 根目录 `.env` 与 Docker Compose（发布部署版）

根目录提供了 `docker-compose.yml` + `.env.example` 作为统一入口：

```bash
cp .env.example .env
# 按环境修改 .env（最小运行集）
docker compose config
docker compose up -d --build
```

约定：

- `.env` 负责简单环境开关、端口和可配置运行目录（如 `HOST_PORT`、`AGENT_AUTH_ENABLED`、`AGENTS_DIR`）；`SERVER_PORT` 主要用于本地非 Docker 运行，默认 compose 会固定容器内监听 `8080`。
- `configs/` 负责结构化业务配置，尤其是 auth、公钥文件、bash 与 container hub。
- `providers/` 负责 provider YAML 注册中心；默认目录可由 `PROVIDERS_DIR` / `agent.providers.external-dir` 覆盖，示例模板建议从 `example/providers/example.yml` 复制并重命名。
- `docker-compose.yml` 会把 `.env` 中的 `*_DIR` 变量用于宿主机 bind mount，同时在容器内把同名变量固定为 `/opt/...` 路径；因此同一份 `.env` 可以同时服务 `make run` 和 `docker compose`。
- 默认 compose 会加入外部网络 `zenmind-network`；启动前需要确保该网络已存在。
- `.env.example` 的默认映射端口是 `11949`（`HOST_PORT`），用于容器化部署示例。
- `docker-compose.yml` 使用 `ports: "${HOST_PORT}:8080"`：
  - `HOST_PORT` 为宿主机暴露端口（推荐使用）。
  - 容器内应用端口固定为 `8080`（compose 会显式覆盖 `SERVER_PORT=8080`）。
- compose 默认显式挂载 runner 固定的 `./configs -> /opt/configs`，并映射这些可配置运行目录：`AGENTS_DIR`、`TEAMS_DIR`、`MODELS_DIR`、`PROVIDERS_DIR`、`TOOLS_DIR`、`MCP_SERVERS_DIR`、`VIEWPORT_SERVERS_DIR`、`VIEWPORTS_DIR`、`SKILLS_MARKET_DIR`、`SCHEDULES_DIR`、`CHATS_DIR`、`ROOT_DIR`、`PAN_DIR`。
- `data/` 仍受应用支持，但默认 Docker 基线不再挂载；只有在你的部署实际使用静态文件目录时，再按需扩展 compose。

#### 文件放置约定

- `Dockerfile` 与 `settings.xml` 保持在项目根目录，匹配 `docker build .` 常见上下文和当前打包脚本路径约定。
- `.env.example` 与 `docker-compose.yml` 保持在项目根目录，作为容器运行基线模板。
- `configs/` 保持在项目根目录，作为结构化配置模板目录。
- `nginx.conf` 当前保持在项目根目录，作为反向代理示例配置；若后续出现多环境部署资产，可统一迁移到 `deploy/nginx/`。
- `.dockerignore` 需要保留：用于缩小 Docker build context，并避免将本地敏感配置（如 `configs/*.yml` / `configs/*.pem` / `configs/**/*.pem`）带入构建上下文。

### 默认配置基线

- 主配置事实源为 `src/main/resources/application.yml`，运行时结构化覆盖来自 `configs/`。
- 可先复制环境变量示例：`cp .env.example .env`，再按环境调整端口与认证开关。
- 再按实际存在的模板复制需要的 `configs/*.example.yml` 与 `configs/**/*.example.*` 为真实配置文件。
- 运行时固定读取 runner 的 `configs/`；Docker 镜像工作目录为 `/opt`，容器内固定使用 `/opt/configs`。`CONFIGS_DIR` 不受支持，设置后会直接启动失败。
- 目录型变量统一使用 `*_DIR` 命名；默认值仍是 `agents/`、`teams/`、`models/`、`providers/`、`tools/`、`skills/`、`viewports/`、`schedules/`、`data/`、`chats/` 等相对目录。
- `agent.cors.enabled` 在主配置中默认是 `false`，即默认不启用 CORS 过滤器。
- `agent.cors.allowed-origin-patterns` 仅匹配请求头 `Origin`，当前服务不读取/校验 `Referer`。
- provider 目录默认是项目根目录 `providers/`，支持热加载，且仅扫描 `.yml/.yaml`。
- provider 文件契约是单文件单对象 flat schema：`key/baseUrl/apiKey/defaultModel/protocols.<PROTOCOL>.endpointPath`。
- 实际模型调用统一使用 `providers/*.yml`；provider 负责基础地址、鉴权和协议级 endpoint 配置。

### settings.xml 说明

- `settings.xml` 作为构建镜像时 Maven 配置，会被 `Dockerfile` 拷贝到 Maven 全局配置目录。
- 当前配置使用 `central` 的阿里云镜像，加速依赖下载。

## 认证配置（JWT）

- HTTP API 的 `Authorization` 请求头格式：`Bearer <token>`
- 当 `agent.auth.enabled=true` 时，`/api/**`（除 `OPTIONS`）都需要 JWT。
- 验签优先级：
  - 若 `agent.auth.local-public-key-file` 或 `agent.auth.local-public-key` 已配置，先使用本地公钥验签；
  - 本地验签失败后，再回退到 `agent.auth.jwks-uri` 拉取的 JWKS 验签。
- 本地公钥模式为启动期加载，更新密钥后需要重启服务生效。

示例（`.env`）：

```env
AGENT_AUTH_ENABLED=true
AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE=local-public-key.pem
AGENT_AUTH_JWKS_URI=https://auth.example.local/api/auth/jwks
AGENT_AUTH_ISSUER=https://auth.example.local
AGENT_AUTH_JWKS_CACHE_SECONDS=300
```

注意：

- 当配置了空的 `local-public-key` / `local-public-key-file` 或非法 PEM 时，服务会在启动时失败（fail-fast）。
- `jwks-uri` / `issuer` / `jwks-cache-seconds` 必须三者同时配置；只配部分会启动失败。
- 当前仅支持 RSA 公钥（与 RS256 验签一致）。

### Voice WebSocket 说明

`/api/ws/voice` 不由本项目提供。本仓库当前只提供 HTTP `/api/*` 接口；如果前端需要语音 WebSocket，请将该路由转发到独立的语音服务。

## Agent 配置快速上手

> 完整 schema 规范、配置规则和已移除字段列表见 [CLAUDE.md #Agent Definition 文件格式](./CLAUDE.md#agent-definition-文件格式)。

- `agents/` 仅支持目录化 Agent：`agents/<key>/agent.yml`
- 前 4 行必须依次为 `key`、`name`、`role`、`description`，且都必须是单行 inline value，方便渐进式披露
- 以 `key` 作为 agentId；若缺失 `key`，视为无效定义
- `modelConfig.modelKey` 为必填，模型信息统一从 `models/*.yml` / `models/*.yaml` 解析
- 服务启动时会先加载一次，并通过目录监听自动刷新
- 可通过 `AGENTS_DIR` 指定目录
- Agent 配置只支持 YAML；若目录内仍有旧 `*.json`，启动和 refresh 都会 fail-fast
- 同目录内 `agent.yml` 与 `agent.yaml` 同时存在时优先 `agent.yml`，并忽略对应 `agent.yaml`
- 目录化 Agent 不再支持 `systemPrompt` 字段；`ONESHOT` / `REACT` 固定读取 `AGENTS.md`
- `PLAN_EXECUTE` 的 `plan` / `execute` / `summary` 必须分别通过 `promptFile` 指定 markdown prompt
- 目录化 Agent 可额外放置：`SOUL.md`、`AGENTS.md`、`AGENTS.plan.md`、`AGENTS.execute.md`、`AGENTS.summary.md`、`memory/memory.md`
- 运行时 system prompt 合并顺序为：`SOUL.md` → 当前阶段选中的 prompt markdown → `memory/memory.md` → skills/tool appendix

### ONESHOT 示例

单轮直答；若配置工具可在单轮中调用工具并收敛最终答案。

`agents/fortune_teller/agent.yml`

```yaml
key: fortune_teller
name: 算命大师
role: 算命大师
icon: "emoji:🔮"
description: 算命大师
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: false
mode: ONESHOT
```

`agents/fortune_teller/AGENTS.md`

```md
你是算命大师
请先问出生日期
```

### REACT 示例

最多 N 轮循环（默认 6）：思考 → 调 1 个工具 → 观察结果。

`agents/react_demo/agent.yml`

```yaml
key: react_demo
name: React Demo
role: React Demo
description: react demo
mode: REACT
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: true
    effort: MEDIUM
toolConfig:
  backends:
    - _bash_
    - datetime
react:
  maxSteps: 6
```

`agents/react_demo/AGENTS.md`

```md
你是算命大师
请先问出生日期
```

### PLAN_EXECUTE 示例

先规划后执行（plan 阶段按 `deepThinking` 选择一回合或两回合）。

`agents/plan_execute_demo/agent.yml`

```yaml
key: plan_execute_demo
name: Plan Execute Demo
role: Plan Execute Demo
description: plan execute demo
mode: PLAN_EXECUTE
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: true
    effort: HIGH
toolConfig:
  backends:
    - _bash_
    - datetime
planExecute:
  plan:
    promptFile: AGENTS.plan.md
    deepThinking: true
  execute:
    promptFile: AGENTS.execute.md
  summary:
    promptFile: AGENTS.summary.md
```

`agents/plan_execute_demo/AGENTS.plan.md`

```md
先规划
```

`agents/plan_execute_demo/AGENTS.execute.md`

```md
再执行
```

`agents/plan_execute_demo/AGENTS.summary.md`

```md
最后总结
```

### Skills 配置示例

```yaml
skills:
  - docx
  - pptx
```

## Models / 工具 / 视图 / 技能目录

> 工具系统设计规范（继承规则、提交协议、action 行为）见 [CLAUDE.md #Tool 系统](./CLAUDE.md#tool-系统)。
> Skills 系统设计见 [CLAUDE.md #Skills 系统](./CLAUDE.md#skills-系统)。
> Viewport 系统设计见 [CLAUDE.md #Viewport 系统](./CLAUDE.md#viewport-系统)。

- 运行目录默认值：
  - agents: `agents/`
  - models: `models/`
  - viewport-servers: `viewport-servers/`
  - viewports: `viewports/`
  - tools: `tools/`
  - skills: `skills/`
- schedules: `schedules/`
- 启动时同步内置 `src/main/resources/tools|skills|schedules` 到外部目录：
  - `TOOLS_DIR`
  - `SKILLS_DIR`
  - `SCHEDULES_DIR`
- 其中 `skills` 当前仅同步内置验证 skill `container_hub_validation`；数学、文本处理、办公文档和 GIF 等 demo skills 请通过 `example/install-example-*` 从 `example/skills/` 初始化。
- `agents|teams|models|mcp-servers|viewport-servers|viewports|providers` 不再内置同步；可通过 `example/install-example-*` 初始化到外层目录。
- `example/agents/` 现已直接提供目录化 Agent 示例，可按目录整体复制到外层运行目录。
- 示例安装为覆盖写入同名文件，但不会清空目标目录，不会删除额外文件。
- 目录监听热重载策略：
  - `agents/` 变更：全量刷新 agent 定义。
  - `tools/` 变更：刷新 tool registry，并按依赖精准刷新受影响 agent。
  - `mcp-servers/` 变更：刷新 mcp server 与 mcp tool registry，并按依赖精准刷新受影响 agent。
  - `viewport-servers/` 变更：刷新 viewport server 与远端 viewport registry，不触发 agent reload。
  - `models/` 变更：刷新 model registry，并按 `modelKey` 依赖精准刷新受影响 agent。
  - `skills/` 变更：仅刷新 skill registry，不触发 agent reload。
  - `schedules/` 变更：刷新计划任务 registry，并增量重编排 cron 触发器。
- 运行中一致性：当前进行中的 run 保持旧快照；reload 后仅新 run 使用新配置。
- `viewports` 支持后缀：`.html`、`.qlc`，默认每 30 秒刷新内存快照。
- `tools`:
  - 仅支持单文件单工具 YAML；前 4 行必须依次为 `name`、`label`、`description`、`type`
  - 普通后端工具：默认 `type: function`
  - 前端工具：通过 `toolType + viewportKey` 声明，触发视图并等待 `/api/submit`
  - 动作工具：通过 `toolAction: true` 声明，触发前端行为但不等待提交
  - 若文件顶层包含 `scaffold: true`，仅作为目录化 Agent 的占位脚手架，不会被运行时注册
- `skills`:
  - 目录结构：`skills/<skill-id>/SKILL.md`（强约束，目录式）
  - 可选子目录：`scripts/`、`references/`、`assets/`
  - `skill-id` 取目录名，`SKILL.md` frontmatter 的 `name/description` 作为元信息。
  - 若 frontmatter 包含 `scaffold: true`，仅作为目录化 Agent 的占位脚手架，不会进入运行时技能目录
  - 隐藏文件和隐藏目录（如 `.DS_Store`）会被静默忽略，不参与 skill 布局校验
- `schedules`:
  - 目录结构：`schedules/<schedule-id>.yml`
  - 前两行固定为 `name: ...`、`description: ...`
  - `description` 仅支持单行披露，不支持 `|` / `>` 多行写法
  - 必填字段：`name`、`description`、`cron`、`agentKey`、`query.message`
  - `teamId` 可选；若填写，`agentKey` 必须属于该 team
  - `environment.zoneId` 可选；不再支持顶层 `zoneId`
  - `query` 必须是对象；支持 `requestId`、`chatId`、`role`、`message`、`references`、`params`、`scene`、`hidden`，其中 `message` 必填
  - `query.stream` 不支持；`query.agentKey` / `query.teamId` 不支持，仍使用顶层字段
  - 不再支持旧扁平格式：顶层字符串 `query`、顶层 `params`、仅配置 `teamId`
  - 内置示例包含 `demo_daily_summary.yml`（每日摘要）和 `demo_viewport_weather_minutely.yml`（每分钟随机城市天气）
- `models`:
  - 目录结构：`models/<model-key>.yml`
  - 关键字段：`key/provider/protocol/modelId/pricing`
  - `protocol` 固定值：`OPENAI`、`ANTHROPIC`（当前 `ANTHROPIC` 仅预留，未实现时会在模型加载阶段拒绝）
- `show_weather_card` 当前仅作为 viewport（`viewports/show_weather_card.html`），不是可调用 tool。
- `mcp-servers`:
  - 目录结构：`mcp-servers/<server-key>.yml`
  - 关键字段：`name`、`transport`、`url/baseUrl`、可选 `headers`
  - agent 通过 `toolConfig.backends` 引用同步后的 MCP 工具名，不直接写 server key
  - 环境变量：`MCP_SERVERS_DIR`
- `viewport-servers`:
  - 目录结构：`viewport-servers/<server-key>.yml`
  - 关键字段：`name`、`transport`、`url/baseUrl`
  - 用于远端 viewport 注册与拉取，与本地 `viewports/` 并存；本地文件和远端注册表互不替代
  - 环境变量：`VIEWPORT_SERVERS_DIR`

### 示例资源初始化

- 示例资源位于 `example/`。
- 一键安装脚本：
  - macOS：`./example/install-example-mac.sh`
  - Linux：`./example/install-example-linux.sh`
  - Windows PowerShell：`.\\example\\install-example-windows.ps1`
- 脚本会覆盖复制：`agents/teams/models/mcp-servers/viewport-servers/viewports/tools/skills/schedules`。

### /api/viewport 约定

- `GET /api/viewport?viewportKey=show_weather_card`
- 返回：
  - `html` 文件：`data = {"html":"<...>"}`
  - `qlc` 文件：`data` 直接是文件内 JSON 对象
- `viewportKey` 不存在时返回 `404`。
- 远端 viewport 来源为 `viewport-servers/`：
  - `viewports/list` 负责注册 summary
  - `viewports/get` 负责透传 payload
  - 不支持 viewports 协议的服务会被跳过并按配置自动重试

### 前端 tool 提交流程

- 当前端工具触发时，SSE `tool.start` / `tool.snapshot` 会包含 `toolType`、`viewportKey`、`toolTimeout`。
- 默认等待超时 5 分钟（可配置）。
- `POST /api/submit` 请求体：`runId` + `toolId` + `params`。
- 成功命中后会释放对应 `runId + toolId` 的等待；未命中返回 `accepted=false`。
- 动作工具触发 `action.start` 后不等待提交，直接返回 `"OK"` 给模型。
- `tool.end` / `action.end` 必须紧跟各自最后一个 `args` 分片，不能延后到 `result` 前补发。

### 运行中引导与中断

- `POST /api/steer`
  - 请求体：`SteerRequest(requestId?, chatId?, runId, steerId?, agentKey?, teamId?, message, planningMode?)`
  - 响应体：`SteerResponse(accepted, status, runId, steerId, detail)`
- `POST /api/interrupt`
  - 请求体：`InterruptRequest(requestId?, chatId?, runId, agentKey?, teamId?, message?, planningMode?)`
  - 响应体：`InterruptResponse(accepted, status, runId, detail)`

## 内置能力

### 内置智能体

- `demoModePlain`（`ONESHOT`）：`Jarvis`，角色为“单次直答示例”。
- `demoModeThinking`（`ONESHOT`）：`Iris`，角色为“深度推理示例”。
- `demoModePlainTooling`（`ONESHOT`）：`Nova`，角色为“单轮工具示例”。
- `demoModeReact`（`REACT`）：`Luna`，角色为“REACT示例”。
- `demoModePlanExecute`（`PLAN_EXECUTE`）：`星策`，角色为“规划执行示例”。
- `demoViewport`（`REACT`）：`极光`，角色为“视图渲染示例”。
- `demoAction`（`ONESHOT`）：`小焰`，角色为“UI动作示例”。
- `demoConfirmDialog`（`REACT`）：`灵犀`，角色为“确认对话示例”。
- `demoDataViewer`（`ONESHOT`）：`天枢`，角色为“文件展示示例”。
- `demoCreateGif`（`REACT`）：`Milo`，角色为“GIF制作示例”。
- `demoDatabase`（`REACT`）：`数枢`，角色为“数据库助手示例”。

中文命名备选池（文档附录）：`清岚`、`星河`、`云舟`、`小岚`、`小满`、`景行`。

### tools/ 目录内的前端与 Action 定义

- `switch_theme(theme)`：主题切换，`theme` 仅支持 `light/dark`。
- `launch_fireworks(durationMs?)`：播放烟花特效，`durationMs` 可选（毫秒）。
- `show_modal(title, content, closeText?)`：弹出模态框，`title/content` 必填，`closeText` 可选。
- `confirm_dialog(question, options?, allowFreeText?)`：前端确认对话框，等待 `/api/submit`。
- `terminal_command_review(...)`：终端命令审查面板，等待 `/api/submit`。

### 内置 Skills

- `container_hub_validation`：Container Hub RUN 沙箱验证清单，要求先做 Bash smoke，再用容器内 Python 写文件。

### 示例 Skills

- 以下 demo skills 位于 `example/skills/`，可通过 `example/install-example-*` 安装到运行目录：
- `docx`：Word 文档读写、内容提取、转换与结构化生成。
- `screenshot`：截图流程示例（含脚本 smoke test）。
- `pptx`：PPT/PPTX 读取、编辑、从提纲生成幻灯片。
- `slack-gif-creator`：GIF 动画创建。

### Java 内置工具

- `_bash_`：Shell 命令执行，需显式配置 `allowed-commands` 与 `allowed-paths` 白名单。
- `datetime`：获取当前或偏移后的日期时间；支持可选 `timezone` 与链式 `offset`，输出包含农历。`offset` 中 `M=月`、`m=分钟`，例如 `+10M+25D`、`+1D-3H+20m`。
- `mock_city_weather`：模拟城市天气数据。

## Container Hub 验证 Agent

仓库提供了示例 agent `demoContainerHubValidator`（目录：`example/agents/demoContainerHubValidator/`），用于验证 `container_hub_bash` 的 RUN 级沙箱能力。该 agent 会先执行 Bash smoke test，再在同一个 run sandbox 中通过 `python3` 写入 `/workspace/validation_report.txt`。

启用前请先配置 `configs/container-hub.yml`（可从 `configs/container-hub.example.yml` 复制）：

```yaml
enabled: true
base-url: http://127.0.0.1:8080
default-environment-id: shell
```

说明：

- `demoContainerHubValidator` 只使用 `container_hub_bash`，不会回退到 `_bash_` 或任何宿主机执行路径。
- 第二阶段的 Python 验证是“容器内 Python”，不是本机 skill 脚本执行。
- RUN 级 session 下，容器中的 `/workspace/<file>` 预期会映射到 host 侧 `CHATS_DIR/<chatId>/<file>`。
- 基础挂载默认存在：`/workspace`、`/root`、`/skills`、`/pan`、`/agent`。
- 默认模式为：`/workspace=rw`、`/root=rw`、`/skills=ro`、`/pan=rw`、`/agent=ro`。
- 若容器环境缺少 `python3`，应将其记录为环境缺口，而不是宣称验证通过。

## Daily Office Agent

仓库提供了通用办公 agent 示例 `dailyOfficeAssistant`（目录：`example/agents/dailyOfficeAssistant/`），默认对接 `agent-container-hub` 中的 `daily-office` 环境，用于：

- 读取、总结和内容级重写 Word 文档（输出新的 `.docx`）
- 根据提纲、摘要或 Word 提炼结果生成 `.pptx`

该 agent 依赖 `example/skills/` 中的 `docx` 与 `pptx` skills；请先通过 `example/install-example-*` 同步示例资产，再在运行目录启用。

启用前提：

1. 在 runner 侧启用 `configs/container-hub.yml`（可从 `configs/container-hub.example.yml` 复制）。
2. `agent-container-hub` 服务中存在可用的 `daily-office` environment。
3. 建议配置 `CHATS_DIR`、`ROOT_DIR`、`PAN_DIR` 等 runner 全局目录，这样 RUN 级沙箱会自动把共享目录挂到容器内 `/workspace`、`/root`、`/pan`。

最小配置示例：

```yaml
enabled: true
base-url: http://127.0.0.1:8080
default-environment-id: daily-office
```

运行约定：

- `dailyOfficeAssistant` 只使用 `container_hub_bash`，不会回退到 `_bash_` 或任何宿主机执行路径。
- Agent 会把容器内 `/workspace` 视为唯一工作目录：上传或已有 chat 资产从 `/workspace` 读取，新生成的 `.docx/.pptx` 也写回 `/workspace`。
- RUN 级 session 下，容器中的 `/workspace/<filename>` 会映射到 host 侧 `CHATS_DIR/<chatId>/<filename>`。
- `docx` / `pptx` skills 提供的是操作手册，不是自动执行器；agent 需要自己通过 `container_hub_bash` 在容器内访问 `/skills/docx` 与 `/skills/pptx` 并执行对应命令。
- 产物可通过 `/api/data` 下载，推荐格式：
  - `/api/data?file=<chatId>%2F<filename>&download=true`
- 若 agent 能从当前上下文确定具体 `chatId`，应返回完整下载链接；否则至少返回上述模板。
- Word“改写”按内容级重写处理，不承诺保留复杂版式、批注、修订痕迹、页眉页脚等高保真格式。

## Container Hub 工具说明

`container_hub_bash` 是 runner 内置的 native/local backend tool，不是 MCP tool。它不会经过 `McpToolInvoker` 或 `mcp-servers/*.yml` 注册链路，而是通过 `ContainerHubClient` 直接请求 `agent-container-hub` 的 `/api/sessions/create`、`/api/sessions/{id}/execute`、`/api/sessions/{id}/stop` REST 接口。

配置前缀固定为：

```yaml
enabled: true
base-url: http://127.0.0.1:8080
default-environment-id: shell
```

说明：

- `meta.sourceType` 在 `/api/tools` 与 `/api/tool?toolName=container_hub_bash` 中应表现为 `local`。
- 该工具的职责是把 runner 的 tool call 桥接为 `agent-container-hub` 的 HTTP session API，而不是提供一个 MCP transport 封装。
- `RUN` 级 sandbox 在创建 session 前会自动准备 `CHATS_DIR/<chatId>` 目录，并把它挂载到容器内的 `/workspace`。
- `/root` 与 `/pan` 分别来自 runner 全局目录 `ROOT_DIR` 与 `PAN_DIR`；`configs/container-hub.yml` 不再单独配置挂载源目录。

挂载模式规则：

- 基础挂载 `/workspace`、`/root`、`/skills`、`/pan`、`/agent` 默认自动存在，不需要在 `agent.yml` 中声明。
- 默认模式为：`/workspace=rw`、`/root=rw`、`/skills=ro`、`/pan=rw`、`/agent=ro`。
- 若需要修改基础挂载模式，继续使用 `sandboxConfig.extraMounts`，仅声明 `destination + mode` 即可覆盖默认模式。
- 所有可选平台挂载和自定义挂载都必须显式声明 `mode: ro|rw`。

`sandboxConfig.extraMounts` 示例：

```yaml
sandboxConfig:
  environmentId: daily-office
  level: run
  extraMounts:
    - platform: tools
      mode: ro
    - platform: models
      mode: rw
    - platform: chats
      mode: ro
    - platform: owner.md
      mode: rw
    - source: /abs/host/path
      destination: /datasets
      mode: ro
    - destination: /skills
      mode: rw
    - destination: /agent
      mode: rw
```

说明：

- `platform: tools/models/...` 表示恢复按需平台目录挂载，同时必须写 `mode`。
- 现支持的额外平台简写包括：`models`、`tools`、`agents`、`viewports`、`viewport-servers`、`teams`、`schedules`、`mcp-servers`、`providers`、`chats`、`owner.md`。
- `source + destination + mode` 表示新增一个自定义宿主目录挂载。
- `destination: /skills` 或 `destination: /agent` 这类写法表示覆盖默认基础挂载模式，不新增第二个挂载。

## Bash 工具配置

`_bash_` 工具必须显式配置命令白名单（`allowed-commands`）和目录白名单（`allowed-paths`）。未配置 `allowed-commands` 时会直接拒绝执行任何命令。工具返回文本包含 `exitCode`、`mode`、`"workingDirectory"`、`stdout`、`stderr`。

`path-checked-commands` 为空时，默认等于 `allowed-commands`；并且只会对 `allowed-commands` 的交集生效。`working-directory` 既决定进程启动目录，也会自动作为 `_bash_` 的基础允许目录。`allowed-paths` 用于追加放行工作目录之外的目录。未配置 `working-directory` 时，`_bash_` 默认取项目运行根目录（通常是 `configs/` 的上级目录），而不是简单的 `${user.dir}`。

`demoScheduleManager` 会优先读取每个 `.yml` 文件前两到三行的 `name` / `description` 披露信息，并按结构化 schedule 契约维护 `environment.zoneId` 与 `query` 对象中的受支持字段（如 `message`、`chatId`、`hidden`）。

`_bash_` 的运行时工具描述会显示当前生效的 `workingDirectory` 与 `shellFeaturesEnabled`，便于定位命令实际执行位置。

`path-check-bypass-commands` 为空时默认关闭。配置后仅对 `allowed-commands` 交集生效；命中的命令会跳过路径参数与重定向目标的目录白名单校验（例如可用于 `git`/`curl` 的命令级放开）。

`shell-features-enabled=false`（默认）时，工具保持严格模式，仅执行单条命令。设置为 `true` 后，遇到高级 shell 语法（管道、重定向、here-doc、`&&`/`||` 等）会切换到 shell 模式执行，同时继续执行命令白名单和路径白名单校验。为安全起见，`source/.`、`eval`、`exec`、进程替换（`<(...)`/`>(...)`）、`coproc`、`fg/bg/jobs` 会被拒绝。

```yaml
working-directory: /opt/app
allowed-paths:
  - /opt/app
  - /opt/data
allowed-commands: ls,pwd,cat,head,tail,top,free,df,git
path-checked-commands: ls,cat,head,tail,git
path-check-bypass-commands: git,curl
shell-features-enabled: false
shell-executable: bash
shell-timeout-ms: 10000
max-command-chars: 16000
```

也可使用环境变量（逗号分隔）：

```bash
AGENT_BASH_WORKING_DIRECTORY=/opt/app
AGENT_BASH_ALLOWED_PATHS=/opt/app,/opt/data
AGENT_BASH_ALLOWED_COMMANDS=ls,pwd,cat,head,tail,top,free,df,git
AGENT_BASH_PATH_CHECKED_COMMANDS=ls,cat,head,tail,git
AGENT_BASH_PATH_CHECK_BYPASS_COMMANDS=git,curl
AGENT_BASH_SHELL_FEATURES_ENABLED=true
AGENT_BASH_SHELL_EXECUTABLE=bash
AGENT_BASH_SHELL_TIMEOUT_MS=10000
AGENT_BASH_MAX_COMMAND_CHARS=16000
```

开启 shell 特性后的常见命令示例：

```bash
rg -n "TODO" src | head -20
cat <<'EOF' > /tmp/sample.txt
hello
EOF
for f in *.md; do echo "$f"; done
```

## 环境变量速查

> 完整环境变量列表（含属性键、默认值和分类说明）见 [CLAUDE.md #Configuration](./CLAUDE.md#configuration)。

常用运维变量：

- 目录型变量统一使用 `*_DIR` 命名；本地 `make run` 直接把这些值当作运行目录，Docker Compose 则把它们当作宿主机 bind source，并在容器内覆盖为 `/opt/...` 目标路径。

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `HOST_PORT` | `11949` | Docker Compose 宿主机暴露端口（映射到容器 `8080`） |
| `SERVER_PORT` | `8080` | 应用 HTTP 监听端口（容器内固定 `8080`；本地非 Docker 运行可覆盖） |
| `AGENTS_DIR` | `agents` | Agent 定义目录 |
| `TEAMS_DIR` | `teams` | Team 定义目录 |
| `MODELS_DIR` | `models` | Model 定义目录 |
| `PROVIDERS_DIR` | `providers` | Provider 定义目录 |
| `TOOLS_DIR` | `tools` | Tool 定义目录 |
| `MCP_SERVERS_DIR` | `mcp-servers` | MCP server 注册目录 |
| `VIEWPORT_SERVERS_DIR` | `viewport-servers` | Viewport server 注册目录 |
| `VIEWPORTS_DIR` | `viewports` | Viewport 目录 |
| `SKILLS_DIR` | `skills` | Skill 目录 |
| `SCHEDULES_DIR` | `schedules` | Schedule 目录 |
| `DATA_DIR` | `data` | 静态文件目录 |
| `CHATS_DIR` | `./chats` | 聊天记忆目录 |
| `AGENT_SCHEDULE_ENABLED` | `true` | 计划任务总开关 |
| `AGENT_SCHEDULE_DEFAULT_ZONE_ID` | 系统时区 | 计划任务默认时区 |
| `AGENT_SCHEDULE_POOL_SIZE` | `4` | 计划任务线程池大小 |
| `AGENT_BASH_WORKING_DIRECTORY` | 项目运行根目录（通常为 `configs/` 上级目录） | Bash 工作目录 |
| `AGENT_BASH_ALLOWED_PATHS` | （空） | Bash 允许路径 |
| `AGENT_BASH_ALLOWED_COMMANDS` | （空=拒绝执行） | Bash 允许命令列表（逗号分隔） |
| `AGENT_BASH_PATH_CHECKED_COMMANDS` | （空=默认等于 allowed-commands） | 启用路径校验的命令列表（逗号分隔） |
| `AGENT_BASH_PATH_CHECK_BYPASS_COMMANDS` | （空=默认关闭） | 跳过路径校验的命令列表（逗号分隔，仅对 allowed-commands 交集生效） |
| `AGENT_BASH_SHELL_FEATURES_ENABLED` | `false` | Bash 高级 shell 语法开关（管道/重定向/here-doc） |
| `AGENT_BASH_SHELL_EXECUTABLE` | `bash` | Bash shell 模式执行器 |
| `AGENT_BASH_SHELL_TIMEOUT_MS` | `10000` | Bash shell 模式超时（ms） |
| `AGENT_BASH_MAX_COMMAND_CHARS` | `16000` | Bash 命令最大字符数 |
| `AGENT_TOOLS_FRONTEND_SUBMIT_TIMEOUT_MS` | `300000` | 前端工具提交超时 |
| `AGENT_AUTH_ENABLED` | `true` | JWT 认证开关 |
| `CHAT_IMAGE_TOKEN_DATA_TOKEN_VALIDATION_ENABLED` | `true` | `/api/data` 的 `t` 参数校验开关（关闭后忽略 `t`） |
| `MEMORY_CHATS_INDEX_SQLITE_FILE` | `chats.db` | 聊天索引 SQLite 文件路径（相对路径按 `CHATS_DIR` 解析） |
| `MEMORY_CHATS_INDEX_AUTO_REBUILD_ON_INCOMPATIBLE_SCHEMA` | `true` | sqlite 索引 schema 不兼容时是否自动备份并重建 |
| `MEMORY_CHATS_K` | `20` | 滑动窗口大小 |
| `LOGGING_AGENT_REQUEST_ENABLED` | `true` | API 请求摘要日志开关（不记录 header） |
| `LOGGING_AGENT_AUTH_ENABLED` | `true` | 认证失败原因日志开关（401/403） |
| `LOGGING_AGENT_EXCEPTION_ENABLED` | `true` | 统一异常日志开关 |
| `LOGGING_AGENT_TOOL_ENABLED` | `true` | tool 调用日志开关 |
| `LOGGING_AGENT_ACTION_ENABLED` | `true` | action 调用日志开关 |
| `LOGGING_AGENT_VIEWPORT_ENABLED` | `true` | viewport API 日志开关 |
| `LOGGING_AGENT_SSE_ENABLED` | `false` | SSE 每条事件日志开关 |
| `LOGGING_AGENT_LLM_INTERACTION_ENABLED` | `true` | LLM 交互日志开关 |

说明：为避免歧义，容器化部署建议使用 `HOST_PORT` 控制宿主机映射端口；`SERVER_PORT` 表示应用监听端口。

### 运行入口

- 开发环境：`make run`
- 生产环境：`docker compose up -d --build`
- 不再支持 `release-local/`、`release/` 或预打包脚本工作流；运维入口统一回到仓库根目录。

### Breaking Change（配置命名迁移）

- 旧配置键已禁用，启动时检测到旧键会直接失败。
- 旧环境变量已禁用，启动时检测到旧变量会直接失败。
- 关键迁移映射：
  - `agent.catalog.*` -> `agent.agents.*`
  - `agent.viewport.*` -> `agent.viewports.*`
  - `agent.capability.*` -> `agent.tools.*`
  - `agent.skill.*` -> `agent.skills.*`
  - `agent.team.*` -> `agent.teams.*`
  - `agent.model.*` -> `agent.models.*`
  - `agent.mcp.*` -> `agent.mcp-servers.*`
  - `memory.chat.*` -> `memory.chats.*`
- 关键环境变量迁移：
  - `AGENT_CONFIG_DIR` -> 固定 runner `configs/` 目录（不再支持覆盖）
  - `AGENT_AGENTS_EXTERNAL_DIR` -> `AGENTS_DIR`
  - `AGENT_TEAMS_EXTERNAL_DIR` -> `TEAMS_DIR`
  - `AGENT_MODELS_EXTERNAL_DIR` -> `MODELS_DIR`
  - `AGENT_PROVIDERS_EXTERNAL_DIR` -> `PROVIDERS_DIR`
  - `AGENT_TOOLS_EXTERNAL_DIR` -> `TOOLS_DIR`
  - `AGENT_SKILLS_EXTERNAL_DIR` -> `SKILLS_DIR`
  - `AGENT_VIEWPORTS_EXTERNAL_DIR` -> `VIEWPORTS_DIR`
  - `AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR` -> `MCP_SERVERS_DIR`
  - `AGENT_VIEWPORT_SERVERS_REGISTRY_EXTERNAL_DIR` -> `VIEWPORT_SERVERS_DIR`
  - `AGENT_SCHEDULE_EXTERNAL_DIR` -> `SCHEDULES_DIR`
  - `AGENT_DATA_EXTERNAL_DIR` -> `DATA_DIR`
  - `MEMORY_CHATS_DIR` -> `CHATS_DIR`

## 静态文件服务（Data）

`data/` 目录用于存放图片、PDF、CSV 等静态文件，通过 `/api/data?file={filename}` 端点提供访问。

- 支持子目录，适合容器环境挂载。
- 默认目录 `data/`，可通过 `DATA_DIR` 环境变量覆盖。
- `file` 参数与 Markdown 路径一对一透传（服务端接收 URL decode 后值），不对 `/data/` 做特殊语义处理。
- 映射示例：
  - Markdown `![示例照片](/data/sample_photo.jpg)` → `file=/data/sample_photo.jpg`
  - Markdown `![图](aaa.jpg)` → `file=aaa.jpg`
- 调用时请对 `file` 做 URL encode（尤其是 `/`、空格、中文等字符）。
- 安全防护：拒绝路径穿越（`..`）、反斜杠（`\`）和符号链接。
- 可选 `t` 参数用于 chat image token 校验；开关为 `agent.chat-image-token.data-token-validation-enabled`。
- 当 `agent.chat-image-token.data-token-validation-enabled=true`（默认）时：
  - 可通过 `t` 绕过 `/api/data` 的 Bearer JWT 要求，并按 chat image token 校验访问范围。
  - 不带 `t` 时，若开启了 `agent.auth.enabled=true`，则仍需 `Authorization: Bearer ...`。
- 当 `agent.chat-image-token.data-token-validation-enabled=false` 时：
  - `GET /api/data` 直接放行，不再要求 chat image token，也不再要求 Bearer JWT。
  - 该模式适合本地调试或可信内网环境，生产环境建议保持开启。

### Content-Disposition 规则

| 类型 | 默认行为 | `?download=true` |
|------|---------|-----------------|
| 图片（`image/*`） | `inline`（浏览器直接展示） | `attachment`（强制下载） |
| 其他文件 | `attachment`（触发下载） | `attachment` |

### 在 Agent 中使用

`demoDataViewer` 智能体演示了如何通过 Markdown 语法展示图片和提供附件下载：

- Markdown 路径定义：
  - 非 `http://` / `https://`：都算相对路径（包括 `sample_diagram.png` 和 `/data/sample_diagram.png`）
  - `http://` / `https://`：绝对路径
- 平台本地图片展示示例：
  - `![描述](sample_diagram.png)`
  - `![描述](/data/sample_photo.jpg)`
- 强制下载图片（`file` 需要 encode）：`[文件名](/api/data?file=%2Fdata%2Fsample_photo.jpg&download=true)`

内置示例文件：

| 文件 | 类型 | 说明 |
|------|------|------|
| `sample_photo.jpg` | 图片 | 示例照片 |
| `sample_diagram.png` | 图片 | 示例架构图 |
| `sample_report.pdf` | 文档 | 示例 PDF 报告 |
| `sample_data.csv` | 数据 | 示例销售数据表 |

可将自定义文件放入 `data/` 目录，并在 Agent 的 prompt markdown（如 `AGENTS.md` 或 `AGENTS.<stage>.md`）中列出文件名即可。

## curl 测试用例

### 会话接口测试

```bash
curl -N -X GET "$BASE_URL/api/chats" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chats?lastRunId=mtoewf3u" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chats?agentKey=demoModePlain&lastRunId=mtoewf3u" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X POST "$BASE_URL/api/read" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656"}'
```

```bash
curl -N -X GET "$BASE_URL/api/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656&includeRawMessages=true" \
  -H "Content-Type: application/json"
```

### Query 回归测试

```bash
# Docker Compose 默认端口（HOST_PORT=11949）
BASE_URL="http://localhost:11949"
# 若本地直接运行 mvn spring-boot:run，可切换为 http://localhost:8080
# BASE_URL="http://localhost:8080"
ACCESS_TOKEN=""
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"元素碳的简介，200字","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"","message":"下一个元素的简介","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个微服务网关的落地方案，100字内","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个机房搬迁风险分析摘要，300字左右","agentKey":"demoModeThinking"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"我周日要搬迁机房到上海，检查下服务器(mac)的硬盘和CPU，然后决定下搬迁条件","agentKey":"demoModeReact"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"规划上海机房明天搬迁的实施计划，重点关注下天气","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"查上海明天天气","agentKey":"demoViewport"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"切换到深色主题","agentKey":"demoAction"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"先检查 /skills/docx 和 pandoc/soffice 是否可用，再总结 /workspace/report.docx 的内容","agentKey":"dailyOfficeAssistant"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"【确认是否有敏感信息】本项目突破传统竖井式系统建设模式，基于1+1+3+N架构（1个企业级数据库、1套OneID客户主数据、3类客群CRM系统整合优化、N个展业数字化应用），打造了覆盖展业全生命周期、贯通公司全客群管理的OneLink分支一体化数智展业服务平台。在数据基础层面，本项目首创企业级数据库及OneID客户主数据运作体系，实现公司全域客户及业务数据物理入湖，并通过事前注册、事中应用管理、事后可分析的机制，实现个人、企业、机构三类客群千万级客户的统一识别与关联。","agentKey":"demoModePlainTooling"}'
```

### 确认对话框（Human-in-the-Loop）

confirm_dialog 是前端工具，LLM 调用后 SSE 流会暂停等待用户提交。需要两个终端配合测试。

**终端 1：发起 query（SSE 流会在 LLM 调用 confirm_dialog 时暂停）**

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我规划周六的旅游，给我几个目的地选项让我选","agentKey":"demoConfirmDialog"}'
```

观察 SSE 输出，当看到 `toolName` 为 `confirm_dialog` 且事件携带 `toolType/viewportKey/toolTimeout` 后，
流会暂停等待。记录事件中的 `runId` 和 `toolId` 值。

**终端 2：提交用户选择（用终端 1 中的 runId 和 toolId 替换占位符）**

```bash
curl -X POST "$BASE_URL/api/submit" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>",
    "toolId": "<TOOL_ID>",
    "params": {
      "selectedOption": "杭州西湖一日游",
      "selectedIndex": 1,
      "freeText": "",
      "isCustom": false
    }
  }'
```

提交后终端 1 的 SSE 流会恢复，LLM 根据用户选择继续输出。
若未命中等待中的 `runId + toolId`，接口仍返回 HTTP 200，但 `accepted=false` / `status=unmatched`。

submit 响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "accepted": true,
    "status": "accepted",
    "runId": "<RUN_ID>",
    "toolId": "<TOOL_ID>",
    "detail": "Frontend submit accepted for runId=<RUN_ID>, toolId=<TOOL_ID>"
  }
}
```

运行中引导示例：

```bash
curl -X POST "$BASE_URL/api/steer" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>",
    "message": "优先给出可执行结论，再补充风险"
  }'
```

运行中断示例：

```bash
curl -X POST "$BASE_URL/api/interrupt" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>"
  }'
```

### 文件展示（Data Viewer）

```bash
# 浏览器直接展示图片
curl "$BASE_URL/api/data?file=sample_diagram.png" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_diagram.png

# 浏览器直接展示图片（file 使用编码后的 /data 路径）
curl "$BASE_URL/api/data?file=%2Fdata%2Fsample_photo.jpg" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg

# 强制下载图片（?download=true）
curl "$BASE_URL/api/data?file=%2Fdata%2Fsample_photo.jpg&download=true" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg

# 下载 CSV 数据表
curl "$BASE_URL/api/data?file=sample_data.csv" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_data.csv
```

```bash
# 与文件展示智能体对话
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"展示所有可用的图片","agentKey":"demoDataViewer"}'
```
