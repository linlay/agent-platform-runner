# agents 示例说明

## 用途

该目录存放示例智能体目录，每个 agent 使用 `example/agents/<agent-key>/` 布局，目录名建议与 `key` 保持一致。

## 命名规范

- 目录命名：`<agent-key>/`
- 配置文件命名：`agent.yml` 或 `agent.yaml`
- `key` 唯一，建议使用小写字母/数字/下划线组合
- 同目录内 `agent.yml` 与 `agent.yaml` 冲突时优先 `agent.yml`
- 前 4 行固定为 `key`、`name`、`role`、`description`

## 最小示例

`example/agents/demo_simple/agent.yml`

```yaml
key: demo_simple
name: Demo Simple
role: 示例角色
description: demo agent
mode: ONESHOT
modelConfig:
  modelKey: bailian-qwen3-max
```

`example/agents/demo_simple/AGENTS.md`

```md
你是示例智能体。
```

## 如何新增

1. 在本目录新增 `<agent-key>/`。
2. 写入 `<agent-key>/agent.yml`，并保证 `modelConfig.modelKey` 在 `example/models` 中可解析。
3. `ONESHOT` / `REACT` 写 `<agent-key>/AGENTS.md`；`PLAN_EXECUTE` 在 YAML 中为每个阶段声明 `promptFile`，并补齐对应 markdown 文件。

## 附带示例

- `demoScheduleManager`：计划任务管理示例（REACT + `_bash_`），用于维护 `schedules/*.yml`，按“渐进式披露阅读”读取文件头，并按结构化 schedule 契约写入 `environment.zoneId` 和 `query` 支持字段（如 `message/chatId/hidden`）。
- `demoImageGenerator`：图像生成示例（REACT + `image.models.list/image.generate/image.edit/image.import`），用于通过 `mcp-server-imagine` 在当前 chat 目录中发现、生成、导入和编辑图片。

## 与外层目录关系

- 源：`example/agents/`
- 推荐目标：`~/.zenmind/agents/<agent-key>/`
- 目录化结构：`agent.yml` + 可选 `SOUL.md` / `AGENTS.md` / `AGENTS.<stage>.md` / `memory/` / `experiences/` / `skills/` / `tools/`
- 示例安装策略：按目录覆盖复制同名文件，不会清空目标目录
- 目录化脚手架中的占位 `memory/*.md`、`experiences/*.md` 默认为空；占位 skill/tool 会带 `scaffold: true`，运行时会自动忽略
