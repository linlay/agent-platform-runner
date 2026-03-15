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

计划任务相关示例：

- `example/agents/demoScheduleManager.yml`：维护 `schedules/*.yml` 的 REACT 智能体，采用“渐进式披露阅读”，优先使用 `head -n 3 *.yml` 读取文件头，并默认用中文 Markdown 表格展示计划任务摘要。
- `example/schedules/demo_viewport_weather_minutely.yml`：每分钟触发 `demoViewport` 查询随机城市天气。

图像生成相关示例：

- `example/mcp-servers/image.json`：`mcp-server-image` 的 runner 注册示例，默认指向 `http://127.0.0.1:11962/mcp`。
- `example/agents/demoImageGenerator.yml`：图像生成 demo，使用 `image.generate`、`image.edit`、`image.import` 三个 MCP 工具。

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

特性：

- 覆盖同名文件
- 自动创建目标目录
- 不清空目录，不删除额外文件

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

1. 启动 `mcp-server-image`。
2. 运行本目录安装脚本，把 `example/mcp-servers/image.json` 和 `example/agents/demoImageGenerator.yml` 同步到项目外层目录。
3. 启动 runner，等待 MCP tool registry 刷新完成。
4. 通过 `GET /api/ap/tools?kind=backend` 确认 `image.generate`、`image.edit`、`image.import` 已出现。
5. 用 `demoImageGenerator` 发起一次文生图请求；成功时，工具返回会带 `asset.relativePath`，图片会落到当前 chat 对应的数据目录。
6. 通过 `GET /api/ap/chat?chatId=...` 检查 `references` 是否包含新图片；若前端未自动内联展示，可结合 `chatImageToken` 访问 `/api/ap/data?file=<chatId/relativePath>&t=<chatImageToken>` 查看。
7. 如果 provider 返回 `model_not_found`、`no available channel` 或同类错误，说明 MCP 链路已通，但当前 provider 没有该默认模型的可用通道；此时请改传你已开通的 `model`，或调整 provider 侧渠道配置。

## mcp-server-image 启动说明

推荐直接在 `../mcp-server-image` 本地启动：

```bash
make run
```

如果使用该仓库自带的 Docker Compose，请不要依赖默认端口映射。当前 `docker-compose.yml` 的 `HOST_PORT` 默认值写成了 `1196s`，本机联调时请显式指定：

```bash
HOST_PORT=11962 docker compose up --build
```

要让“真的画图”成立，还需要满足以下外部前提：

- `mcp-server-image` 已配置可用的 provider API Key，例如 Babelark 或 Poe。
- `mcp-server-image` 的 `/data` 与 runner 的数据目录指向同一份 chat 数据根目录。
- 如果 `mcp-server-image` 通过 Docker 挂载到类似 `/tmp/runner-data -> /data`，则 runner 也应把 `AGENT_DATA_EXTERNAL_DIR` 指到同一个宿主机目录，例如 `/tmp/runner-data`。

若以上条件不满足，runner 仍能识别这组 MCP 工具，但工具调用会在生成阶段失败，此时 `demoImageGenerator` 应直接回报真实错误，而不是声称图片已生成。
