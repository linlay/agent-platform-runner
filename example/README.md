# Example 目录

`example/` 提供可直接复制到运行目录的示例资产，用于快速启用 demo 能力。

## 目录结构

- `agents/`：demo 智能体定义
- `teams/`：demo 团队定义
- `models/`：demo 模型定义
- `mcp-servers/`：demo MCP Server 定义
- `viewport-servers/`：demo Viewport Server 定义
- `viewports/`：demo 前端视图
- `tools/`：demo 工具定义
- `skills/`：demo 技能目录
- `schedules/`：demo 计划任务定义
- `providers/`：平台无关的 provider 配置模板

计划任务相关示例：

- `example/agents/demoScheduleManager.yml`：维护 `schedules/*.yml` 的 REACT 智能体，采用“渐进式披露阅读”，并按结构化 schedule 契约读写 `agentKey / teamId / environment.zoneId / query` 支持字段（如 `message / chatId / hidden`）。
- `example/schedules/demo_viewport_weather_minutely.yml`：每分钟触发 `demoViewport` 查询随机城市天气。

图像生成相关示例：

- `example/mcp-servers/imagine.yml`：`mcp-server-imagine` 的 runner 注册示例，默认指向 `http://127.0.0.1:11962/mcp`，`serverKey` 使用 `imagine`。
- `example/agents/demoImageGenerator.yml`：图像生成 demo，使用 `image.models.list`、`image.generate`、`image.edit`、`image.import` 四个 MCP 工具。

办公文档相关示例：

- `example/agents/dailyOfficeAssistant.yml`：daily-office 容器办公助手，使用 `container_hub_bash` 配合 `docx` / `pptx` skills 读取和重写 Word、按提纲生成 PPTX。
- `example/agents/demoContainerHubValidator.yml`：RUN 级 container-hub 验证助手，验证 Bash 与容器内 Python 写文件链路。
- `example/skills/docx/`：Word 文档读写与结构化编辑技能。
- `example/skills/pptx/`：PPT/PPTX 读取、编辑与生成技能。

命名说明：

- `mcp-server-imagine` 是服务名称，可统一承载图像和未来的视频能力。
- `mcp-servers/imagine.yml` 是 runner 侧的 MCP 注册文件。
- `image.*` 是当前图像能力的工具命名空间，未来同一服务可继续新增 `video.*`。
- 历史残留的 `mcp-servers/image.yml` 已废弃；安装脚本会在同步前删除它，避免与 `imagine.yml` 同时存在时产生重复工具告警。

## 一键安装脚本

脚本会将 `example/` 中以下目录覆盖复制到项目外层根目录：

- `agents`
- `teams`
- `models`
- `mcp-servers`
- `viewport-servers`
- `viewports`
- `tools`
- `skills`
- `schedules`
- `providers`

特性：

- 覆盖同名文件
- 自动创建目标目录
- 不清空目录
- 会删除过期的 `mcp-servers/image.yml`，避免与 `mcp-servers/imagine.yml` 同时存在时触发重复 `image.*` 工具注册

执行方式：

```bash
# macOS
./example/install-example-mac.sh

# Linux
./example/install-example-linux.sh
```

```powershell
# Windows PowerShell
.\example\install-example-windows.ps1
```

## image MCP 联调步骤

1. 启动 `mcp-server-imagine`。
2. 运行本目录安装脚本，把 `example/mcp-servers/imagine.yml` 和 `example/agents/demoImageGenerator.yml` 同步到项目外层目录；脚本会先删除已废弃的 `mcp-servers/image.yml`。
3. 启动 runner，等待 MCP tool registry 刷新完成。
4. 通过 `GET /api/tools?kind=backend` 确认 `image.models.list`、`image.generate`、`image.edit`、`image.import` 已出现。
5. 用 `demoImageGenerator` 发起一次文生图请求；agent 会先用 `image.models.list` 获取模型 schema，再执行生成。成功时，工具返回会带 `asset.relativePath`，图片会落到当前 chat 对应的数据目录。
6. 通过 `GET /api/chat?chatId=...` 检查 `references` 是否包含新图片；若前端未自动内联展示，可结合 `chatImageToken` 访问 `/api/data?file=<chatId/relativePath>&t=<chatImageToken>` 查看。
7. 如果 provider 返回 `model_not_found`、`no available channel` 或同类错误，说明 MCP 链路已通，但当前 provider 没有该默认模型的可用通道；此时请改传你已开通的 `defaultModel`，或调整 provider 侧渠道配置。

## mcp-server-imagine 启动说明

推荐直接在 `../mcp-server-imagine` 本地启动：

```bash
make run
```

如果使用该仓库自带的 Docker Compose，请不要依赖默认端口映射。当前 `docker-compose.yml` 的 `HOST_PORT` 默认值写成了 `1196s`，本机联调时请显式指定：

```bash
HOST_PORT=11962 docker compose up --build
```

要让“真的画图”成立，还需要满足以下外部前提：

- `mcp-server-imagine` 已配置可用的 provider API Key，例如 Babelark 或 Poe。
- `mcp-server-imagine` 的 `/data` 与 runner 的数据目录指向同一份 chat 数据根目录。
- 如果 `mcp-server-imagine` 通过 Docker 挂载到类似 `/tmp/runner-data -> /data`，则 runner 也应把 `AGENT_DATA_EXTERNAL_DIR` 指到同一个宿主机目录，例如 `/tmp/runner-data`。

若以上条件不满足，runner 仍能识别这组 MCP 工具，但工具调用会在生成阶段失败，此时 `demoImageGenerator` 应直接回报真实错误，而不是声称图片已生成。
