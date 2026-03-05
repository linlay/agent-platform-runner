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

## 一键安装脚本

脚本会将 `example/` 中以下目录覆盖复制到项目外层根目录：

- `agents`
- `teams`
- `models`
- `mcp-servers`
- `viewports`
- `tools`
- `skills`

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
