# springai-agent-platform

本仓库是可独立构建和部署的 Spring AI Agent 服务，已将流式事件模块源码内置到本仓库，不依赖外置 jar。

> 详细架构设计、数据模型、API 契约和开发约束见 [CLAUDE.md](./CLAUDE.md)。

## 提供接口

- `GET /api/ap/agents`: 智能体列表
- `GET /api/ap/teams`: 团队列表（team 元数据，含成员 `agentKeys`）
- `GET /api/ap/skills`: 技能列表（支持 `tag` 过滤）
- `GET /api/ap/tools`: 工具列表（支持 `tag`、`kind=backend|frontend|action` 过滤）
- `GET /api/ap/chats`: 会话列表（支持 `lastRunId` 增量查询）
- `POST /api/ap/read`: 标记单个会话已读
- `GET /api/ap/chat?chatId=...`: 会话详情（默认返回快照事件流）
- `GET /api/ap/chat?chatId=...&includeRawMessages=true`: 会话详情（附带原始 `rawMessages`）
- `GET /api/ap/data?file={filename}&download=true|false`: 静态文件服务（图片 inline / 附件 download）
- `GET /api/ap/viewport?viewportKey=...`: 获取工具/动作视图内容
- `POST /api/ap/query`: 提问接口（默认返回标准 SSE；`requestId` 可省略，缺省时等于 `runId`）
- `POST /api/ap/submit`: Human-in-the-loop 提交接口

## 不兼容升级说明

- 元数据单项接口 `/api/ap/agent`、`/api/ap/skill`、`/api/ap/tool` 已下线，统一使用列表接口全量同步。
- 会话索引使用 SQLite（默认文件：`chats.db`）。
- 聊天正文与回放仍使用 `./chats/{chatId}.json`，接口行为保持兼容。

## 返回格式约定

- `POST /api/ap/query` 返回 SSE event stream。
- `/api/ap/query` 流结束时会追加传输层终止帧 `data:[DONE]`（不属于业务事件模型，也不会出现在 `/api/ap/chat` 的历史 `events` 中）。
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
- `GET /api/ap/chat` 默认始终返回 `events`；仅当 `includeRawMessages=true` 时才返回 `rawMessages`。
- 事件协议仅支持 Event Model v2，不兼容旧命名（如 `query.message`、`message.start|delta|end`、`message.snapshot`）。

`GET /api/ap/agents` 示例（`role` 为顶层字段）：

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
        "tools": ["datetime", "mock_city_weather", "confirm_dialog"],
        "skills": []
      }
    }
  ]
}
```

`GET /api/ap/teams` 返回结构：

- 列表项：`teamId`, `name`, `icon`, `agentKeys`, `meta.invalidAgentKeys`, `meta.defaultAgentKey`, `meta.defaultAgentKeyValid`

`GET /api/ap/skills` 返回结构：

- 列表项：`key`, `name`, `description`, `meta.promptTruncated`

`GET /api/ap/tools` 返回结构：

- 列表项：`key`, `name`, `description`, `meta.kind`, `meta.toolType`, `meta.toolApi`, `meta.viewportKey`, `meta.strict`

`GET /api/ap/chats` 示例：

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

`GET /api/ap/chats?lastRunId=mtoewf3u` 示例：

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

`POST /api/ap/read` 示例：

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

`GET /api/ap/chat` 的 `data` 结构如下：

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
│   ├── auth/
│   └── providers/
├── docker-compose.yml
├── example/
│   ├── agents/
│   ├── teams/
│   ├── models/
│   ├── mcp-servers/
│   ├── viewports/
│   ├── tools/
│   ├── skills/
│   ├── schedules/
│   ├── install-example-mac.sh
│   ├── install-example-linux.sh
│   └── install-example-windows.ps1
├── src/
├── data/
├── schedules/
├── release-scripts/
│   ├── mac/
│   └── windows/
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
set -a
. ./.env
set +a
mvn spring-boot:run
```

默认端口 `8080`（来自 `src/main/resources/application.yml` 的 `SERVER_PORT` 默认值）。
若 `.env` 或环境变量覆盖了端口（例如开发常用 `11949`），则以覆盖值为准。
注意：直接执行 `mvn spring-boot:run` 不会自动加载根目录 `.env`；若本地依赖 `AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE` 等环境变量，请先在当前 shell 导入 `.env`。

### 根目录 `.env` 与 Docker Compose（发布部署版）

根目录提供了 `docker-compose.yml` + `.env.example` 作为统一入口：

```bash
cp .env.example .env
# 按环境修改 .env（最小运行集）
docker compose config
docker compose up -d --build
```

约定：

- `.env` 负责简单环境开关与端口（如 `HOST_PORT`、`SERVER_PORT`、`AGENT_AUTH_ENABLED`）。
- `configs/` 负责结构化大配置，尤其是 auth、公钥文件、bash、tts 与多 provider（`agent.providers.*`）。
- `.env.example` 的默认映射端口是 `11949`（`HOST_PORT`），用于容器化部署示例。
- `docker-compose.yml` 使用 `ports: "${HOST_PORT}:8080"`：
  - `HOST_PORT` 为宿主机暴露端口（推荐使用）。
  - 容器内应用端口固定为 `8080`（`environment.SERVER_PORT=8080`）。

### release-local 配置说明

通过 hub 仓库 `setup-mac.sh` 的首次安装流程时，会先执行 `./release-scripts/mac/package-local.sh`，再在 `release-local/` 写入运行时配置：

- `configs/*.yml`：由 `configs/*.example.yml` / `configs/**/*.example.*` 复制生成
- `.env`：由安装流程按环境生成（若存在 `.env.example` 会优先复制）

`release-scripts/mac/package-local.sh` 只负责构建产物，不负责生成运行时配置。

#### 跨平台脚本入口

- macOS（Bash）:
  - `./release-scripts/mac/package-local.sh`
  - `./release-scripts/mac/package-docker.sh`
  - `./release-scripts/mac/start-local.sh`
  - `./release-scripts/mac/stop-local.sh`
- Windows（非 WSL / Git Bash，PowerShell 原生）:
  - `.\release-scripts\windows\package-local.ps1`
  - `.\release-scripts\windows\package-docker.ps1`
  - `.\release-scripts\windows\start-local.ps1`
  - `.\release-scripts\windows\stop-local.ps1`

说明：`release-scripts/` 仅保留平台实现脚本目录，不再保留根目录转发脚本。

#### 文件放置约定

- `release-scripts/` 仅放打包与运行脚本（按平台分目录），不放部署配置资产。
- `Dockerfile` 与 `settings.xml` 保持在项目根目录，匹配 `docker build .` 常见上下文和当前打包脚本路径约定。
- `.env.example` 与 `docker-compose.yml` 保持在项目根目录，作为容器运行基线模板。
- `configs/` 保持在项目根目录，作为结构化配置模板目录。
- `nginx.conf` 当前保持在项目根目录，作为反向代理示例配置；若后续出现多环境部署资产，可统一迁移到 `deploy/nginx/`。
- `.dockerignore` 需要保留：用于缩小 Docker build context，并避免将本地敏感配置（如 `configs/*.yml` / `configs/**/*.pem`）带入构建上下文。

### 默认配置基线

- 主配置事实源为 `src/main/resources/application.yml`，运行时结构化覆盖来自 `configs/`。
- 可先复制环境变量示例：`cp .env.example .env`，再按环境调整端口与认证开关。
- 再复制需要的 `configs/*.example.yml` 与 `configs/**/*.example.*` 为真实配置文件。
- 运行时默认读取 `./configs`；发布/容器默认读取 `/opt/configs`；可通过 `AGENT_CONFIG_DIR` 覆盖。
- `agent.cors.enabled` 在主配置中默认是 `false`，即默认不启用 CORS 过滤器。
- `agent.cors.allowed-origin-patterns` 仅匹配请求头 `Origin`，当前服务不读取/校验 `Referer`。
- 实际模型调用统一使用 `agent.providers.*`；provider 负责基础地址、鉴权和协议级 endpoint 配置。

### settings.xml 说明

- `settings.xml` 作为构建镜像时 Maven 配置，会被 `Dockerfile` 拷贝到 Maven 全局配置目录。
- 当前配置使用 `central` 的阿里云镜像，加速依赖下载。

## 认证配置（JWT）

- HTTP API 的 `Authorization` 请求头格式：`Bearer <token>`
- 当 `agent.auth.enabled=true` 时，`/api/ap/**`（除 `OPTIONS`）都需要 JWT。
- Voice WebSocket（`/api/ap/ws/voice`）在 `agent.voice.ws.auth-required=true` 时仅接受 URL query token：`?access_token=<token>`，不会读取 `Authorization` 头。
- 验签优先级：
  - 若 `agent.auth.local-public-key-file` 或 `agent.auth.local-public-key` 已配置，先使用本地公钥验签；
  - 本地验签失败后，再回退到 `agent.auth.jwks-uri` 拉取的 JWKS 验签。
- 本地公钥模式为启动期加载，更新密钥后需要重启服务生效。

示例（`.env`）：

```env
AGENT_AUTH_ENABLED=true
AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE=auth/local-public-key.pem
AGENT_AUTH_JWKS_URI=https://auth.example.local/api/auth/jwks
AGENT_AUTH_ISSUER=https://auth.example.local
AGENT_AUTH_JWKS_CACHE_SECONDS=300
```

注意：

- 当配置了空的 `local-public-key` / `local-public-key-file` 或非法 PEM 时，服务会在启动时失败（fail-fast）。
- `jwks-uri` / `issuer` / `jwks-cache-seconds` 必须三者同时配置；只配部分会启动失败。
- 当前仅支持 RSA 公钥（与 RS256 验签一致）。

### Voice WebSocket 联调（wscat）

```bash
wscat -c "ws://127.0.0.1:11948/api/ap/ws/voice?access_token=xxx"
```

验证要点：

- 缺少 `access_token` 时，握手会被拒绝（401 或升级失败）。
- 仅携带 `Authorization` 头、但 URL 无 `access_token` 时，同样会被拒绝。

## Agent 配置快速上手

> 完整 schema 规范、配置规则和已移除字段列表见 [CLAUDE.md #Agent Definition 文件格式](./CLAUDE.md#agent-definition-文件格式)。

- `agents/` 同时支持 `*.json`、`*.yml`、`*.yaml`
- 以 `key` 作为 agentId；若缺失 `key`，回退为文件名（不含 `.json/.yml/.yaml`）
- `modelConfig.modelKey` 为必填，模型信息统一从 `models/*.json` 解析
- 服务启动时会先加载一次，并通过目录监听自动刷新
- 可通过 `AGENT_AGENTS_EXTERNAL_DIR` 指定目录
- YAML 推荐作为新建格式，天然支持多行字符串
- `systemPrompt` 在 JSON 中兼容 `"""` 多行写法（仅 `systemPrompt`）；YAML 推荐使用 `|` / `>`
- 同 basename 或同 `key` 冲突时，YAML 优先于 JSON

### ONESHOT 示例

单轮直答；若配置工具可在单轮中调用工具并收敛最终答案。新建示例建议优先用 YAML。

```yaml
key: fortune_teller
name: 算命大师
icon: "emoji:🔮"
description: 算命大师
modelConfig:
  modelKey: bailian-qwen3-max
  reasoning:
    enabled: false
mode: ONESHOT
plain:
  systemPrompt: |
    你是算命大师
    请先问出生日期
```

如需继续使用 JSON，旧写法仍兼容：

```json
{
  "key": "fortune_teller",
  "name": "算命大师",
  "icon": "emoji:🔮",
  "description": "算命大师",
  "modelConfig": {
    "modelKey": "bailian-qwen3-max",
    "reasoning": { "enabled": false }
  },
  "mode": "ONESHOT",
  "plain": {
    "systemPrompt": "你是算命大师"
  }
}
```

### REACT 示例（JSON 兼容写法）

最多 N 轮循环（默认 6）：思考 → 调 1 个工具 → 观察结果。

```json
{
  "mode": "REACT",
  "modelConfig": {
    "modelKey": "bailian-qwen3-max",
    "reasoning": { "enabled": true, "effort": "MEDIUM" }
  },
  "toolConfig": {
    "backends": ["_bash_", "datetime"],
    "frontends": [],
    "actions": []
  },
  "react": {
    "systemPrompt": """
你是算命大师
请先问出生日期
""",
    "maxSteps": 6
  }
}
```

### PLAN_EXECUTE 示例

先规划后执行（plan 阶段按 `deepThinking` 选择一回合或两回合）。

```json
{
  "mode": "PLAN_EXECUTE",
  "modelConfig": {
    "modelKey": "bailian-qwen3-max",
    "reasoning": { "enabled": true, "effort": "HIGH" }
  },
  "toolConfig": {
    "backends": ["_bash_", "datetime", "mock_city_weather"],
    "frontends": [],
    "actions": []
  },
  "planExecute": {
    "plan": { "systemPrompt": "先规划", "deepThinking": true },
    "execute": { "systemPrompt": "再执行" },
    "summary": { "systemPrompt": "最后总结" }
  }
}
```

### Skills 配置示例

```json
{
  "skills": ["math_basic", "text_utils"],
  "toolConfig": {
    "backends": ["_skill_run_script_"]
  }
}
```

## Models / 工具 / 视图 / 技能目录

> 工具系统设计规范（继承规则、提交协议、action 行为）见 [CLAUDE.md #Tool 系统](./CLAUDE.md#tool-系统)。
> Skills 系统设计见 [CLAUDE.md #Skills 系统](./CLAUDE.md#skills-系统)。
> Viewport 系统设计见 [CLAUDE.md #Viewport 系统](./CLAUDE.md#viewport-系统)。

- 运行目录默认值：
  - agents: `agents/`
  - models: `models/`
  - viewports: `viewports/`
  - tools: `tools/`
  - skills: `skills/`
- schedules: `schedules/`
- 启动时同步内置 `src/main/resources/tools|skills|schedules` 到外部目录：
  - `AGENT_TOOLS_EXTERNAL_DIR`
  - `AGENT_SKILLS_EXTERNAL_DIR`
  - `AGENT_SCHEDULE_EXTERNAL_DIR`
- `agents|teams|models|mcp-servers|viewports` 不再内置同步；可通过 `example/install-example-*` 初始化到外层目录。
- 示例安装为覆盖写入同名文件，但不会清空目标目录，不会删除额外文件。
- 目录监听热重载策略：
  - `agents/` 变更：全量刷新 agent 定义。
  - `tools/` 变更：刷新 tool registry，并按依赖精准刷新受影响 agent。
  - `mcp-servers/` 变更：刷新 mcp server 与 mcp tool registry，并按依赖精准刷新受影响 agent。
  - `models/` 变更：刷新 model registry，并按 `modelKey` 依赖精准刷新受影响 agent。
  - `skills/` 变更：仅刷新 skill registry，不触发 agent reload。
  - `schedules/` 变更：刷新计划任务 registry，并增量重编排 cron 触发器。
- 运行中一致性：当前进行中的 run 保持旧快照；reload 后仅新 run 使用新配置。
- `viewports` 支持后缀：`.html`、`.qlc`，默认每 30 秒刷新内存快照。
- `tools`:
  - 后端工具文件：`*.backend`
  - 前端工具文件：`*.frontend`
  - 动作文件：`*.action`
  - 文件内容均为模型工具定义 JSON（`{"tools":[...]}`）
- `skills`:
  - 目录结构：`skills/<skill-id>/SKILL.md`（强约束，目录式）
  - 可选子目录：`scripts/`、`references/`、`assets/`
  - `skill-id` 取目录名，`SKILL.md` frontmatter 的 `name/description` 作为元信息。
- `schedules`:
  - 目录结构：`schedules/<schedule-id>.json`
  - 必填字段：`cron`、`query`
  - 目标字段：`agentKey` 或 `teamId`（至少一个）
  - 可选字段：`enabled`、`name`、`zoneId`、`params`
  - 若仅配置 `teamId`，则读取 team 的 `defaultAgentKey` 作为默认执行智能体
- `models`:
  - 目录结构：`models/<model-key>.json`
  - 关键字段：`key/provider/protocol/modelId/pricing`
  - `protocol` 固定值：`OPENAI`、`ANTHROPIC`（当前 `ANTHROPIC` 仅预留，未实现时会在模型加载阶段拒绝）
- `show_weather_card` 当前仅作为 viewport（`viewports/show_weather_card.html`），不是可调用 tool。

### 示例资源初始化

- 示例资源位于 `example/`。
- 一键安装脚本：
  - macOS：`./example/install-example-mac.sh`
  - Linux：`./example/install-example-linux.sh`
  - Windows PowerShell：`.\\example\\install-example-windows.ps1`
- 脚本会覆盖复制：`agents/teams/models/mcp-servers/viewports/tools/skills/schedules`。

### /api/ap/viewport 约定

- `GET /api/ap/viewport?viewportKey=show_weather_card`
- 返回：
  - `html` 文件：`data = {"html":"<...>"}`
  - `qlc` 文件：`data` 直接是文件内 JSON 对象
- `viewportKey` 不存在时返回 `404`。

### 前端 tool 提交流程

- 当前端工具触发时，SSE `tool.start` / `tool.snapshot` 会包含 `toolType`、`toolKey`、`toolTimeout`。
- 默认等待超时 5 分钟（可配置）。
- `POST /api/ap/submit` 请求体：`runId` + `toolId` + `params`。
- 成功命中后会释放对应 `runId + toolId` 的等待；未命中返回 `accepted=false`。
- 动作工具触发 `action.start` 后不等待提交，直接返回 `"OK"` 给模型。
- `tool.end` / `action.end` 必须紧跟各自最后一个 `args` 分片，不能延后到 `result` 前补发。

## 内置能力

### 内置智能体

- `demoModePlain`（`ONESHOT`）：`Jarvis`，角色为“单次直答示例”。
- `demoModeThinking`（`ONESHOT`）：`Iris`，角色为“深度推理示例”。
- `demoModePlainTooling`（`ONESHOT`）：`Nova`，角色为“单轮工具示例”。
- `demoModeReact`（`REACT`）：`Luna`，角色为“REACT示例”。
- `demoModePlanExecute`（`PLAN_EXECUTE`）：`星策`，角色为“规划执行示例”。
- `demoViewport`（`REACT`）：`极光`，角色为“视图渲染示例”。
- `demoAction`（`ONESHOT`）：`小焰`，角色为“UI动作示例”。
- `demoAgentCreator`（`PLAN_EXECUTE`）：`小匠`，角色为“Agent创建示例”。
- `demoMathSkill`（`ONESHOT`）：`Leo`，角色为“数学技能示例”。
- `demoConfirmDialog`（`REACT`）：`灵犀`，角色为“确认对话示例”。
- `demoDataViewer`（`ONESHOT`）：`天枢`，角色为“文件展示示例”。
- `demoCreateGif`（`REACT`）：`Milo`，角色为“GIF制作示例”。

中文命名备选池（文档附录）：`清岚`、`星河`、`云舟`、`小岚`、`小满`、`景行`。

### 内置 Action

- `switch_theme(theme)`：主题切换，`theme` 仅支持 `light/dark`。
- `launch_fireworks(durationMs?)`：播放烟花特效，`durationMs` 可选（毫秒）。
- `show_modal(title, content, closeText?)`：弹出模态框，`title/content` 必填，`closeText` 可选。

### 内置 Skills

- `screenshot`：截图流程示例（含脚本 smoke test）。
- `math_basic`：算术计算（add/sub/mul/div/pow/mod）。
- `math_stats`：统计计算（summary/count/sum/min/max/mean/median/mode/stdev）。
- `text_utils`：文本指标（字符/词数/行数，可选空白归一化）。
- `slack-gif-creator`：GIF 动画创建。

### 内置工具

- `_skill_run_script_`：执行 skills 目录下脚本或临时 Python 脚本。
- `_bash_`：Shell 命令执行，需显式配置 `allowed-commands` 与 `allowed-paths` 白名单。
- `datetime`：获取当前或偏移后的日期时间；支持可选 `timezone` 与链式 `offset`，输出包含农历。
- `mock_city_weather`：模拟城市天气数据。
- `agent_file_create`：创建/更新 agent JSON 文件（校验 key 仅允许 `A-Za-z0-9_-`，最长 64）。

## Bash 工具配置

`_bash_` 工具必须显式配置命令白名单（`allowed-commands`）和目录白名单（`allowed-paths`）。未配置 `allowed-commands` 时会直接拒绝执行任何命令。工具返回文本包含 `exitCode`、`mode`、`"workingDirectory"`、`stdout`、`stderr`。

`path-checked-commands` 为空时，默认等于 `allowed-commands`；并且只会对 `allowed-commands` 的交集生效。`working-directory` 仅决定进程启动目录，不会自动加入 `allowed-paths`。

`path-check-bypass-commands` 为空时默认关闭。配置后仅对 `allowed-commands` 交集生效；命中的命令会跳过路径参数与重定向目标的目录白名单校验（例如可用于 `git`/`curl` 的命令级放开）。

`shell-features-enabled=false`（默认）时，工具保持严格模式，仅执行单条命令。设置为 `true` 后，遇到高级 shell 语法（管道、重定向、here-doc、`&&`/`||` 等）会切换到 shell 模式执行，同时继续执行命令白名单和路径白名单校验。为安全起见，`source/.`、`eval`、`exec`、进程替换（`<(...)`/`>(...)`）、`coproc`、`fg/bg/jobs` 会被拒绝。

```yaml
agent:
  tools:
    bash:
      working-directory: /opt/app
      allowed-paths:
        - /opt/app
        - /opt/data
      allowed-commands:
        - ls,pwd,cat,head,tail,top,free,df,git
      path-checked-commands:
        - ls,cat,head,tail,git
      path-check-bypass-commands:
        - git,curl
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

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `HOST_PORT` | `11949` | Docker Compose 宿主机暴露端口（映射到容器 `8080`） |
| `SERVER_PORT` | `8080` | 应用 HTTP 监听端口（容器内固定 `8080`；本地非 Docker 运行可覆盖） |
| `AGENT_AGENTS_EXTERNAL_DIR` | `agents` | Agent 定义目录 |
| `AGENT_MODELS_EXTERNAL_DIR` | `models` | Model 定义目录 |
| `AGENT_VIEWPORTS_EXTERNAL_DIR` | `viewports` | Viewport 目录 |
| `AGENT_TOOLS_EXTERNAL_DIR` | `tools` | 工具目录 |
| `AGENT_SKILLS_EXTERNAL_DIR` | `skills` | 技能目录 |
| `AGENT_SCHEDULE_EXTERNAL_DIR` | `schedules` | 计划任务目录 |
| `AGENT_SCHEDULE_ENABLED` | `true` | 计划任务总开关 |
| `AGENT_SCHEDULE_DEFAULT_ZONE_ID` | 系统时区 | 计划任务默认时区 |
| `AGENT_SCHEDULE_POOL_SIZE` | `4` | 计划任务线程池大小 |
| `AGENT_DATA_EXTERNAL_DIR` | `data` | 静态文件目录 |
| `AGENT_BASH_WORKING_DIRECTORY` | `${user.dir}` | Bash 工作目录 |
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
| `CHAT_IMAGE_TOKEN_DATA_TOKEN_VALIDATION_ENABLED` | `true` | `/api/ap/data` 的 `t` 参数校验开关（关闭后忽略 `t`） |
| `MEMORY_CHATS_DIR` | `./chats` | 聊天记忆目录 |
| `MEMORY_CHATS_INDEX_SQLITE_FILE` | `chats.db` | 聊天索引 SQLite 文件路径（相对路径按 `MEMORY_CHATS_DIR` 解析） |
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
  - `AGENT_EXTERNAL_DIR` -> `AGENT_AGENTS_EXTERNAL_DIR`
  - `AGENT_VIEWPORT_EXTERNAL_DIR` -> `AGENT_VIEWPORTS_EXTERNAL_DIR`
  - `AGENT_SKILL_EXTERNAL_DIR` -> `AGENT_SKILLS_EXTERNAL_DIR`
  - `AGENT_TEAM_EXTERNAL_DIR` -> `AGENT_TEAMS_EXTERNAL_DIR`
  - `AGENT_MODEL_EXTERNAL_DIR` -> `AGENT_MODELS_EXTERNAL_DIR`
  - `AGENT_MCP_*` -> `AGENT_MCP_SERVERS_*`
  - `MEMORY_CHAT_*` -> `MEMORY_CHATS_*`

## 静态文件服务（Data）

`data/` 目录用于存放图片、PDF、CSV 等静态文件，通过 `/api/ap/data?file={filename}` 端点提供访问。

- 支持子目录，适合容器环境挂载。
- 默认目录 `data/`，可通过 `AGENT_DATA_EXTERNAL_DIR` 环境变量覆盖。
- `file` 参数与 Markdown 路径一对一透传（服务端接收 URL decode 后值），不对 `/data/` 做特殊语义处理。
- 映射示例：
  - Markdown `![示例照片](/data/sample_photo.jpg)` → `file=/data/sample_photo.jpg`
  - Markdown `![图](aaa.jpg)` → `file=aaa.jpg`
- 调用时请对 `file` 做 URL encode（尤其是 `/`、空格、中文等字符）。
- 安全防护：拒绝路径穿越（`..`）、反斜杠（`\`）和符号链接。
- 可选 `t` 参数用于 chat image token 校验；开关为 `agent.chat-image-token.data-token-validation-enabled`（默认开启，可关闭）。

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
- 强制下载图片（`file` 需要 encode）：`[文件名](/api/ap/data?file=%2Fdata%2Fsample_photo.jpg&download=true)`

内置示例文件：

| 文件 | 类型 | 说明 |
|------|------|------|
| `sample_photo.jpg` | 图片 | 示例照片 |
| `sample_diagram.png` | 图片 | 示例架构图 |
| `sample_report.pdf` | 文档 | 示例 PDF 报告 |
| `sample_data.csv` | 数据 | 示例销售数据表 |

可将自定义文件放入 `data/` 目录，并在 Agent 的 `systemPrompt` 中列出文件名即可。

## curl 测试用例

### 会话接口测试

```bash
curl -N -X GET "$BASE_URL/api/ap/chats" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/ap/chats?lastRunId=mtoewf3u" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X POST "$BASE_URL/api/ap/read" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656"}'
```

```bash
curl -N -X GET "$BASE_URL/api/ap/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/ap/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656&includeRawMessages=true" \
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
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"元素碳的简介，200字","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"","message":"下一个元素的简介","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个微服务网关的落地方案，100字内","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个机房搬迁风险分析摘要，300字左右","agentKey":"demoModeThinking"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"我周日要搬迁机房到上海，检查下服务器(mac)的硬盘和CPU，然后决定下搬迁条件","agentKey":"demoModeReact"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"规划上海机房明天搬迁的实施计划，重点关注下天气","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"查上海明天天气","agentKey":"demoViewport"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"切换到深色主题","agentKey":"demoAction"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Content-Type: application/json" \
  -d '{"message":"请计算 (2+3)*4，并说明过程","agentKey":"demoMathSkill"}'
```

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"【确认是否有敏感信息】本项目突破传统竖井式系统建设模式，基于1+1+3+N架构（1个企业级数据库、1套OneID客户主数据、3类客群CRM系统整合优化、N个展业数字化应用），打造了覆盖展业全生命周期、贯通公司全客群管理的OneLink分支一体化数智展业服务平台。在数据基础层面，本项目首创企业级数据库及OneID客户主数据运作体系，实现公司全域客户及业务数据物理入湖，并通过事前注册、事中应用管理、事后可分析的机制，实现个人、企业、机构三类客群千万级客户的统一识别与关联。","agentKey":"demoModePlainTooling"}'
```

### 确认对话框（Human-in-the-Loop）

confirm_dialog 是前端工具，LLM 调用后 SSE 流会暂停等待用户提交。需要两个终端配合测试。

**终端 1：发起 query（SSE 流会在 LLM 调用 confirm_dialog 时暂停）**

```bash
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我规划周六的旅游，给我几个目的地选项让我选","agentKey":"demoConfirmDialog"}'
```

观察 SSE 输出，当看到 `toolName` 为 `confirm_dialog` 且事件携带 `toolType/toolKey/toolTimeout` 后，
流会暂停等待。记录事件中的 `runId` 和 `toolId` 值。

**终端 2：提交用户选择（用终端 1 中的 runId 和 toolId 替换占位符）**

```bash
curl -X POST "$BASE_URL/api/ap/submit" \
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

### 文件展示（Data Viewer）

```bash
# 浏览器直接展示图片
curl "$BASE_URL/api/ap/data?file=sample_diagram.png" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_diagram.png

# 浏览器直接展示图片（file 使用编码后的 /data 路径）
curl "$BASE_URL/api/ap/data?file=%2Fdata%2Fsample_photo.jpg" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg

# 强制下载图片（?download=true）
curl "$BASE_URL/api/ap/data?file=%2Fdata%2Fsample_photo.jpg&download=true" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg

# 下载 CSV 数据表
curl "$BASE_URL/api/ap/data?file=sample_data.csv" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_data.csv
```

```bash
# 与文件展示智能体对话
curl -N -X POST "$BASE_URL/api/ap/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"展示所有可用的图片","agentKey":"demoDataViewer"}'
```
