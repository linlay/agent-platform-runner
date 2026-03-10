# Example 目录

`example/` 提供可直接复制到运行目录的示例资产，用于快速启用 demo 能力。

## 目录结构

- `agents/`：demo 智能体定义
- `teams/`：demo 团队定义
- `models/`：demo 模型定义
- `mcp-servers/`：demo MCP Server 定义
- `viewports/`：demo 前端视图
- `tools/`：demo 工具定义
- `skills/`：demo 技能目录
- `schedules/`：demo 计划任务定义

## 终端助手示例

- `example/agents/terminalAssistant.yml`：终端辅助智能体，使用 `PLAN_EXECUTE`，先列计划，再一次性提交完整命令清单给 `terminal_command_review`。
- `example/tools/terminal_command_review.yml`：前端工具定义，约定由外部前端审批、执行并通过 `/api/ap/submit` 回传命令结果。
- `example/viewports/terminal_command_review.html`：runner 独立页面的兜底预览视图，可展示命令清单并进行整体批准/拒绝。

使用说明：

- 该示例依赖 term-webclient 一类的外部前端代理与本地命令执行能力；runner 自身不直接执行这些终端命令。
- 终端命令执行时间通常长于普通前端工具，请将 `AGENT_TOOLS_FRONTEND_SUBMIT_TIMEOUT_MS` 调大到适合你的执行窗口，例如 `900000`（15 分钟）。

## 一键安装脚本

脚本会将 `example/` 中以下目录覆盖复制到项目外层根目录：

- `agents`
- `teams`
- `models`
- `mcp-servers`
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
