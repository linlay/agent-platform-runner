# agents 示例说明

## 用途

该目录存放示例智能体定义文件，文件名建议与 `key` 保持一致。支持 JSON 和 YAML，推荐新建 `.yml`。

## 命名规范

- 文件后缀：`.json`、`.yml`、`.yaml`
- 文件命名：`<agent-key>.<ext>`
- `key` 唯一，建议使用小写字母/数字/下划线组合
- 同 basename 或同 `key` 冲突时，YAML 优先于 JSON

## 最小示例

```yaml
key: demo_simple
name: Demo Simple
description: demo agent
role: 示例角色
mode: ONESHOT
modelConfig:
  modelKey: bailian-qwen3-max
```

## 如何新增

1. 在本目录新增 `<agent-key>.yml`（或 `.json` / `.yaml`）。
2. 保证 `modelConfig.modelKey` 在 `example/models` 中可解析。
3. 运行示例安装脚本同步到外层 `agents/`。

## 附带示例

- `demoScheduleManager`：计划任务管理示例（REACT + `_bash_`），用于维护 `schedules/*.yml`，并优先读取文件前两到三行的 `name`/`description` 披露信息。
  使用提示：建议将 `AGENT_BASH_WORKING_DIRECTORY` 指向项目根目录。`working-directory` 会自动放行该目录及其子路径；如需访问其他目录或创建/改写文件，再按需配置 `AGENT_BASH_ALLOWED_PATHS`、放行相关命令，并启用 `AGENT_BASH_SHELL_FEATURES_ENABLED=true`。
- `terminalAssistant`：终端辅助智能体示例（PLAN_EXECUTE + `terminal_command_review`），用于把完整命令清单交给外部前端逐步审批或批量放行。

## 与外层目录关系

- 源：`example/agents/`
- 目标：项目根目录 `agents/`
- 策略：覆盖同名文件，保留额外文件
