# agent-platform-runner

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
      "key": "ops_assistant",
      "name": "Ops Assistant",
      "description": "负责日常运维查询与执行建议",
      "role": "运维助手",
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
      "agentKey": "ops_assistant",
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
      "agentKey": "ops_assistant",
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

`GET /api/chats?agentKey=ops_assistant&lastRunId=mtoewf3u` 示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "chatId": "d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656",
      "chatName": "元素碳的简介，100",
      "agentKey": "ops_assistant",
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
├── compose.yml
├── src/
├── data/
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

推荐把可配置运行时目录统一放到项目外，再通过 `.env` 指向它们。`configs/` 不是可配置目录，固定使用 runner 自带的 `./configs`（容器内固定挂载到 `/opt/configs`）。当前默认已经把 `providers/models/mcp-servers/viewport-servers` 这四类动态注册目录统一收口到 `registries/` 父目录下，与静态启动配置 `configs/` 区分开；若你需要保留模板文件，也建议放到单独的 `example.registries/` 下，避免运行时扫描到示例文件。例如代码仓库保留在当前工作区，而运行目录放在共享目录：

```bash
PROVIDERS_DIR=/Users/you/runtime/runner/registries/providers
MODELS_DIR=/Users/you/runtime/runner/registries/models
MCP_SERVERS_DIR=/Users/you/runtime/runner/registries/mcp-servers
VIEWPORT_SERVERS_DIR=/Users/you/runtime/runner/registries/viewport-servers
OWNER_DIR=/Users/you/runtime/runner/owner
AGENTS_DIR=/Users/you/runtime/runner/agents
TEAMS_DIR=/Users/you/runtime/runner/teams
ROOT_DIR=/Users/you/runtime/runner/root
SCHEDULES_DIR=/Users/you/runtime/runner/schedules
CHATS_DIR=/Users/you/runtime/runner/chats
PAN_DIR=/Users/you/runtime/runner/pan
SKILLS_MARKET_DIR=/Users/you/runtime/runner/skills-market
```

### 根目录 `.env` 与 Docker Compose（发布部署版）

根目录提供了 `compose.yml` + `.env.example` 作为统一入口：

```bash
cp .env.example .env
# 按环境修改 .env（最小运行集）
docker compose config
docker compose up -d --build
```

约定：

- `.env` 负责简单环境开关、端口和可配置运行目录（如 `HOST_PORT`、`AGENT_AUTH_ENABLED`、`AGENTS_DIR`、`OWNER_DIR`、`AGENT_CONTAINER_HUB_BASE_URL`）；`SERVER_PORT` 主要用于本地非 Docker 运行。
- `configs/` 负责结构化业务配置，尤其是 auth、公钥文件、bash 与 container hub。
- 运行时业务目录既可以保留在仓库内默认路径，也可以通过 `.env` 的 `*_DIR` 指向宿主机其他路径覆盖默认值；其中 `providers/models/mcp-servers/viewport-servers` 默认回落到 `./runtime/registries/*`，其余目录默认回落到 `./runtime/*`。
- 若把这四类动态注册目录外置到共享根目录，保持使用 `registries/` 作为它们的父目录命名，并把模板放到独立的 `example.registries/`。
- 本地 `make run` 会先加载 `.env`，因此 `*_DIR` 会直接作为应用读取目录生效；Docker Compose 继续复用同一份 `.env`，但这些 `*_DIR` 在容器里只用于宿主机 bind mount source。
- `compose.yml` 会把根目录 `.env` 只读挂载到容器内 `/tmp/runner-host.env`，并通过 `SANDBOX_HOST_DIRS_FILE` 指向这份 mapping 文件；`sandbox_bash` 创建 container-hub session 时，会优先从这份文件读取宿主机路径作为 mount source，而不是使用容器内 `/opt/...` 路径。
- 默认 compose 会加入外部网络 `zenmind-network`；启动前需要确保该网络已存在。
- `.env.example` 的默认映射端口是 `11949`（`HOST_PORT`），用于容器化部署示例；所有 `*_DIR` 都支持改成绝对宿主机路径。
- `.env.example` 默认把 `AGENT_CONTAINER_HUB_BASE_URL` 指向 `http://host.docker.internal:11960`，用于容器内访问宿主机上的 Container Hub；compose 同时注入 `host.docker.internal:host-gateway` 以兼容 Linux Docker。
- Docker Compose / release bundle 会显式启用 `SPRING_PROFILES_ACTIVE=docker`，应用在该 profile 下固定使用容器内 `/opt/agents`、`/opt/chats`、`/opt/root` 以及 `/opt/registries/{providers,models,mcp-servers,viewport-servers}` 等路径。
- `compose.yml` 使用 `ports: "${HOST_PORT}:8080"`：
  - `HOST_PORT` 为宿主机暴露端口（推荐使用）。
  - 容器内应用端口固定为 `8080`（由 `docker` profile 固定，不依赖 `.env` 中的 `SERVER_PORT`）。
- compose 默认显式挂载 runner 固定的 `./configs -> /opt/configs`、`./.env -> /tmp/runner-host.env`，并映射这些可配置运行目录：`PROVIDERS_DIR`、`MODELS_DIR`、`MCP_SERVERS_DIR`、`VIEWPORT_SERVERS_DIR`、`OWNER_DIR`、`AGENTS_DIR`、`TEAMS_DIR`、`ROOT_DIR`、`SCHEDULES_DIR`、`CHATS_DIR`、`PAN_DIR`、`SKILLS_MARKET_DIR`。
- Docker 容器内这些目录固定映射到 `/opt/*`，其中四类动态注册目录固定映射到 `/opt/registries/*`；`.env` 中的 `*_DIR` 不再直接决定容器内 Spring 绑定值。
- 应用内部仍按 `AGENTS_DIR` 的父目录推导 owner 路径；`OWNER_DIR` 只是部署层的宿主机 bind mount 入口和 sandbox host mapping 键，不新增 Spring `external-dir` 配置键。
- `data/` 仍受应用支持，但默认 Docker 基线不再挂载；只有在你的部署实际使用静态文件目录时，再按需扩展 compose。

### 版本化离线 bundle（release 交付版）

当前仓库同时支持“源码仓库内 docker compose 部署”和“版本化离线 bundle 交付”两种入口。离线 bundle 适合上传制品库、GitHub Release 或拷贝到部署机后直接解压运行。

发布入口：

```bash
make release
ARCH=amd64 make release
ARCH=arm64 make release
```

约定：

- 发布版本单一来源是根目录 `VERSION`，格式固定为 `vX.Y.Z`
- 正常发布流程是先更新根目录 `VERSION`，再执行 `make release`
- 最终 bundle 输出到 `dist/release/`
- 单次构建只产出一个目标架构 bundle
- release 会先在宿主机执行 `mvn -DskipTests clean package`，再构建只包含运行时的镜像
- release bundle 内置 `images/agent-platform-runner.tar`、`compose.release.yml`、启动脚本、配置模板和 `.env.example`，不再预创建 `runtime/` 目录骨架
- release bundle 继续依赖外部 Docker 网络 `zenmind-network`
- `*_DIR` 仍默认指向 `./runtime/*`，若这些目录不存在，`./start.sh` 会按最终生效路径自动创建
- release 默认依赖宿主机 Maven 配置、宿主机网络与宿主机代理；源码仓库里的 `docker compose up -d --build` 仍然可能走容器内构建
- release 基础镜像默认是 `eclipse-temurin:21-jre-jammy`
- 可通过 `RELEASE_BASE_IMAGE` 直接替换远端镜像地址
- 更推荐先本地 `docker pull` + `docker tag`，再通过 `RELEASE_BASE_IMAGE_LOCAL` 走本地基础镜像兜底

部署端标准步骤：

```bash
tar -xzf dist/release/agent-platform-runner-v0.1.0-linux-amd64.tar.gz
cd agent-platform-runner
cp .env.example .env
# 按需复制 configs/*.example.* 为真实配置
docker network create zenmind-network   # 若网络还不存在
./start.sh
```

更多说明见 `docs/versioned-release-bundle.md`。

#### 国内环境如何替换 release 基础镜像

如果 `make release` 卡在拉 `eclipse-temurin:21-jre-jammy`，不要把某一个国内镜像站当成默认答案。先从候选镜像站里任选一个做 `docker pull` 验证，只有验证成功后，再进入 release 流程。

当前文档给出几个候选完整镜像引用示例：

```bash
m.daocloud.io/docker.io/library/eclipse-temurin:21-jre-jammy
docker.1ms.run/library/eclipse-temurin:21-jre-jammy
registry.dockermirror.com/library/eclipse-temurin:21-jre-jammy
```

说明：

- 以上只是候选示例，不保证任一镜像站长期可用
- 以你当前网络下 `docker pull <candidate-image>` 能成功为准
- 如果 `docker pull` 失败，不要继续 `docker tag`，直接切换下一个候选

推荐顺序是先验证可拉，再本地兜底：

```bash
docker pull <candidate-image>
docker tag <candidate-image> agent-platform-runner-base:jre21
RELEASE_BASE_IMAGE_LOCAL=agent-platform-runner-base:jre21 ARCH=arm64 make release
```

这种方式更稳，因为：

- `docker pull` 可以单独重试，不和 buildx 构建绑在一起
- `docker tag` 后，release 直接使用本地镜像别名
- 能绕开 buildx 在构建过程中向镜像站直接拉 blob 时遇到的 `503`
- 本地基础镜像必须和目标构建架构一致；`ARCH=arm64` 时必须准备 `linux/arm64` 镜像，`ARCH=amd64` 时必须准备 `linux/amd64` 镜像
- `docker load` 成功不代表架构正确；如果日志出现 `InvalidBaseImagePlatform ... pulled with platform "linux/amd64", expected "linux/arm64"`，说明你导入的是错误架构的基础镜像

如果你验证某个候选镜像地址可拉成功，也可以直接走远端镜像替换：

```bash
RELEASE_BASE_IMAGE=<candidate-image> ARCH=arm64 make release
```

变量规则如下：

- 默认值：`eclipse-temurin:21-jre-jammy`
- 远端替换：`RELEASE_BASE_IMAGE=<完整镜像地址>`
- 本地兜底：`RELEASE_BASE_IMAGE_LOCAL=<你本地的镜像别名>`
- 若同时设置，优先使用 `RELEASE_BASE_IMAGE_LOCAL`

这些变量只影响 release 运行时镜像，不影响宿主机的 Maven 打包，也不影响仓库根目录的 `docker compose up -d --build`。

#### 文件放置约定

- `Dockerfile` 与 `settings.xml` 保持在项目根目录，匹配 `docker build .` 常见上下文和当前打包脚本路径约定。
- `scripts/release-assets/Dockerfile.release` 作为 release 专用运行时镜像定义，只接收宿主机构建出来的 jar。
- `.env.example` 与 `compose.yml` 保持在项目根目录，作为容器运行基线模板。
- `VERSION` 保持在项目根目录，作为 release 版本单一来源。
- `configs/` 保持在项目根目录，作为结构化配置模板目录。
- `scripts/release-assets/` 保存 release bundle 模板资产。
- `nginx.conf` 当前保持在项目根目录，作为反向代理示例配置；若后续出现多环境部署资产，可统一迁移到 `deploy/nginx/`。
- `.dockerignore` 需要保留：用于缩小 Docker build context，并避免将本地敏感配置（如 `configs/*.yml` / `configs/*.pem` / `configs/**/*.pem`）带入构建上下文。

### 默认配置基线

- 主配置事实源为 `src/main/resources/application.yml`，运行时结构化覆盖来自 `configs/`。
- 可先复制环境变量示例：`cp .env.example .env`，再按环境调整端口与认证开关。
- 再按实际存在的模板复制需要的 `configs/*.example.yml` 与 `configs/**/*.example.*` 为真实配置文件。
- 运行时固定读取 runner 的 `configs/`；Docker 镜像工作目录为 `/opt`，容器内固定使用 `/opt/configs`。`CONFIGS_DIR` 不受支持，设置后会直接启动失败。
- 目录型变量统一使用 `*_DIR` 命名；默认值中 `providers/models/mcp-servers/viewport-servers` 统一归到 `runtime/registries/*`，其余运行目录保持 `runtime/*` 相对目录。
- `agent.cors.enabled` 在主配置中默认是 `false`，即默认不启用 CORS 过滤器。
- `agent.cors.allowed-origin-patterns` 仅匹配请求头 `Origin`，当前服务不读取/校验 `Referer`。
- provider 目录默认是项目根目录下的 `runtime/registries/providers/`（或 `PROVIDERS_DIR` 覆盖目录），支持热加载，且仅扫描 `.yml/.yaml`。
- provider 文件契约是单文件单对象 flat schema：`key/baseUrl/apiKey/defaultModel/protocols.<PROTOCOL>.endpointPath`。
- 实际模型调用统一使用 `runtime/registries/providers/*.yml`（或 `PROVIDERS_DIR` 覆盖目录）；provider 负责基础地址、鉴权和协议级 endpoint 配置。

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
- `modelConfig.modelKey` 为必填，模型信息统一从 `runtime/registries/models/*.yml` / `runtime/registries/models/*.yaml`（或 `MODELS_DIR` 覆盖目录）解析
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
skillConfig:
  skills:
    - docx
    - pptx
```

- 只要配置了 `skillConfig.skills`，运行时会自动把 `sandbox_bash` 合并进有效 backend tools，不需要再显式写到 `toolConfig.backends`。
- 若某个 stage 显式写了 `toolConfig: null`，仍会保留这个由 skills 隐式带入的 `sandbox_bash`；`null` 只清空手动声明的普通工具。

### Runtime Context 配置示例

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

- `sandbox`：从 `agent-container-hub` 的 `GET /api/environments/{name}/agent-prompt` 读取 environment prompt，并与本地 `sandboxConfig` 摘要一起注入 system prompt。
- `sandbox` 是强依赖：若 environment prompt 缺失、为空或请求失败，本次请求会直接失败，并输出错误日志。
- `all-agents`：注入全部已注册 agent 的 YAML 风格头部摘要，便于指挥官 agent 预先了解子 agent 能力边界。

## Models / 工具 / 视图 / 技能目录

> 工具系统设计规范（继承规则、提交协议、action 行为）见 [CLAUDE.md #Tool 系统](./CLAUDE.md#tool-系统)。
> Skills 系统设计见 [CLAUDE.md #Skills 系统](./CLAUDE.md#skills-系统)。
> Viewport 系统设计见 [CLAUDE.md #Viewport 系统](./CLAUDE.md#viewport-系统)。

- 运行目录约定：
  - agents: `runtime/agents/`
  - teams: `runtime/teams/`
  - models: `runtime/registries/models/`
  - providers: `runtime/registries/providers/`
  - mcp-servers: `runtime/registries/mcp-servers/`
  - viewport-servers: `runtime/registries/viewport-servers/`
  - skills-market: `runtime/skills-market/`（可通过 `SKILLS_MARKET_DIR` 覆盖）
  - schedules: `runtime/schedules/`（可通过 `SCHEDULES_DIR` 覆盖）
  - chats: `runtime/chats/`
  - root: `runtime/root/`
  - pan: `runtime/pan/`
- 四类动态注册目录默认归到 `registries/` 下，例如 `registries/providers/`、`registries/models/`、`registries/mcp-servers/`、`registries/viewport-servers/`；静态启动配置仍使用 runner 根目录 `configs/`。
- runner 不再同步任何内置 skill / schedule 资源；内置 tool 与 viewport 固定来自 `src/main/resources`，skill 与 schedule 始终来自运行目录或 `*_DIR` 覆盖目录，其余 agents、teams、models、providers、mcp-servers、viewport-servers 仍由外部目录提供。
- 目录监听热重载策略：
  - `runtime/agents/` 变更：全量刷新 agent 定义。
  - `runtime/registries/mcp-servers/` 变更：刷新 mcp server 与 mcp tool registry，并按依赖精准刷新受影响 agent。
  - `runtime/registries/viewport-servers/` 变更：刷新 viewport server 与远端 viewport registry，不触发 agent reload。
  - `runtime/registries/models/` 变更：刷新 model registry，并按 `modelKey` 依赖精准刷新受影响 agent。
  - `runtime/skills-market/`（或 `SKILLS_MARKET_DIR` 覆盖目录）变更：仅刷新 skill registry，不触发 agent reload。
  - `runtime/schedules/`（或 `SCHEDULES_DIR` 覆盖目录）变更：刷新计划任务 registry，并增量重编排 cron 触发器。
- 运行中一致性：当前进行中的 run 保持旧快照；reload 后仅新 run 使用新配置。
- 内置 `viewports` 支持后缀：`.html`、`.qlc`，默认每 30 秒刷新内存快照。
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
- `models`:
  - 目录结构：`registries/models/<model-key>.yml`
  - 关键字段：`key/provider/protocol/modelId/pricing`
  - `protocol` 固定值：`OPENAI`、`ANTHROPIC`（当前 `ANTHROPIC` 仅预留，未实现时会在模型加载阶段拒绝）
- `mcp-servers`:
  - 目录结构：`registries/mcp-servers/<server-key>.yml`
  - 关键字段：`name`、`transport`、`url/baseUrl`、可选 `headers`
  - agent 通过 `toolConfig.backends` 引用同步后的 MCP 工具名，不直接写 server key
  - 环境变量：`MCP_SERVERS_DIR`
- `viewport-servers`:
  - 目录结构：`registries/viewport-servers/<server-key>.yml`
  - 关键字段：`name`、`transport`、`url/baseUrl`
  - 用于远端 viewport 注册与拉取，与本地 `viewports/` 并存；本地文件和远端注册表互不替代
  - 环境变量：`VIEWPORT_SERVERS_DIR`

### /api/viewport 约定

- `GET /api/viewport?viewportKey=<viewport-key>`
- 返回：
  - `html` 文件：`data = {"html":"<...>"}`
  - `qlc` 文件：`data` 直接是文件内 JSON 对象
- `viewportKey` 不存在时返回 `404`。
- 远端 viewport 来源为 `registries/viewport-servers/`：
  - `viewports/list` 负责注册 summary，单个条目至少包含 `viewportKey` 和 `viewportType`
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

### 系统内置资源

- `tools/`：仅保留系统内置工具定义，例如 `_bash_`、`datetime`、`sandbox_bash`、plan tools、`confirm_dialog`。
- `viewports/`：保留系统内置 viewport 模板。
- skill 与 schedule 不再提供任何内置 starter 内容，完全由 `runtime/skills-market/`、`runtime/schedules/` 或对应 `*_DIR` 覆盖目录提供。
- runner 不再分发任何内置 demo viewport、UI action tool 或示例 agent。

### Java 内置工具

- `_bash_`：Shell 命令执行，需显式配置 `allowed-commands` 与 `allowed-paths` 白名单。
- `datetime`：获取当前或偏移后的日期时间；支持可选 `timezone` 与链式 `offset`，输出包含农历。`offset` 中 `M=月`、`m=分钟`，例如 `+10M+25D`、`+1D-3H+20m`。

## Container Hub 工具说明

`sandbox_bash` 是 runner 内置的本地 backend tool，用于在沙箱容器中执行命令。

配置前缀固定为：

```yaml
enabled: true
base-url: ${AGENT_CONTAINER_HUB_BASE_URL:http://host.docker.internal:11960}
default-environment-id: shell
```

说明：

- `meta.sourceType` 在 `/api/tools` 与 `/api/tool?toolName=sandbox_bash` 中应表现为 `local`。
- 建议通过 `.env` 中的 `AGENT_CONTAINER_HUB_BASE_URL` 配置 Container Hub 地址；容器化部署模板默认使用 `http://host.docker.internal:11960`。
- `RUN` 级 sandbox 在创建 session 前会自动准备 `CHATS_DIR/<chatId>` 目录，并把它挂载到容器内的 `/workspace`。
- sandbox mount source 会优先读取 `SANDBOX_HOST_DIRS_FILE` 指向的 mapping 文件；在 Docker Compose / release bundle 中，这通常是根目录 `.env` 被挂载后的 `/tmp/runner-host.env`。如果当前运行目录已被容器重写成 `/opt/...`，但 mapping 文件里没有对应的 `*_DIR`，runner 会直接报配置错误。
- 当 Container Hub 运行在宿主机时，建议把 `.env` 中的 `*_DIR` 写成该宿主机可直接访问的真实路径。
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
    - platform: owner
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
- 现支持的额外平台简写包括：`models`、`tools`、`agents`、`viewports`、`viewport-servers`、`teams`、`schedules`、`mcp-servers`、`providers`、`chats`、`owner`。
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

- 目录型变量统一使用 `*_DIR` 命名；本地 `make run` 直接把这些值当作运行目录，Docker Compose 则把它们当作宿主机 bind source，并在容器内覆盖为 `/opt/...` 目标路径。对 sandbox mount 来说，`.env` 中的原始 `*_DIR` 仍然是宿主机 source-of-truth。

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `HOST_PORT` | `11949` | Docker Compose 宿主机暴露端口（映射到容器 `8080`） |
| `SERVER_PORT` | `8080` | 应用 HTTP 监听端口（本地非 Docker 运行可覆盖；Docker `docker` profile 内固定 `8080`） |
| `PROVIDERS_DIR` | `runtime/registries/providers` | 本地运行时的 Provider 定义目录；Docker 中仅作为宿主机挂载 source，容器内固定映射到 `/opt/registries/providers` |
| `MODELS_DIR` | `runtime/registries/models` | 本地运行时的 Model 定义目录；Docker 中仅作为宿主机挂载 source，容器内固定映射到 `/opt/registries/models` |
| `MCP_SERVERS_DIR` | `runtime/registries/mcp-servers` | 本地运行时的 MCP server 注册目录；Docker 中仅作为宿主机挂载 source，容器内固定映射到 `/opt/registries/mcp-servers` |
| `VIEWPORT_SERVERS_DIR` | `runtime/registries/viewport-servers` | 本地运行时的 Viewport server 注册目录；Docker 中仅作为宿主机挂载 source，容器内固定映射到 `/opt/registries/viewport-servers` |
| `OWNER_DIR` | `runtime/owner` | 本地运行时的 owner 目录；Docker 中仅作为宿主机挂载 source |
| `AGENTS_DIR` | `runtime/agents` | 本地运行时的 Agent 定义目录；Docker 中仅作为宿主机挂载 source |
| `TEAMS_DIR` | `runtime/teams` | 本地运行时的 Team 定义目录；Docker 中仅作为宿主机挂载 source |
| `ROOT_DIR` | `runtime/root` | 本地运行时的 runner 根目录；Docker 中仅作为宿主机挂载 source |
| `SCHEDULES_DIR` | `runtime/schedules` | 本地运行时的 Schedule 目录；Docker 中仅作为宿主机挂载 source |
| `CHATS_DIR` | `runtime/chats` | 本地运行时的聊天记忆目录；Docker 中仅作为宿主机挂载 source |
| `PAN_DIR` | `runtime/pan` | 本地运行时的 pan 目录；Docker 中仅作为宿主机挂载 source |
| `SKILLS_MARKET_DIR` | `runtime/skills-market` | 本地运行时的 Skill market 目录；Docker 中仅作为宿主机挂载 source |
| `DATA_DIR` | `data` | 静态文件目录 |
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
- 源码仓库部署：`docker compose up -d --build`
- 版本化离线交付：`make release`，部署端解压 bundle 后执行 `./start.sh`

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
  - `AGENT_TOOLS_EXTERNAL_DIR` -> 内置 classpath tools（不再支持外部目录）
  - `AGENT_SKILLS_EXTERNAL_DIR` -> `SKILLS_MARKET_DIR`
  - `AGENT_VIEWPORTS_EXTERNAL_DIR` -> 内置 classpath viewports（不再支持外部目录）
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

- 下载响应里的 `filename*` 只使用文件基名，不包含 `chatId/` 或其他子目录路径。

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

## 测试用例

手动 curl 测试用例已迁移至 [docs/manual-test-cases.md](./docs/manual-test-cases.md)。
