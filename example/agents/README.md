# agents 示例说明

## 用途

该目录存放示例智能体定义文件，文件名建议与 `key` 保持一致。这里仍保留扁平 YAML 作为示例事实源，生成后的运行时目录推荐使用目录化 Agent 布局。

## 命名规范

- 文件后缀：`.yml`、`.yaml`
- 文件命名：`<agent-key>.<ext>`
- `key` 唯一，建议使用小写字母/数字/下划线组合
- 同 basename 冲突时优先 `.yml`
- 前 4 行固定为 `key`、`name`、`role`、`description`

## 最小示例

```yaml
key: demo_simple
name: Demo Simple
role: 示例角色
description: demo agent
mode: ONESHOT
modelConfig:
  modelKey: bailian-qwen3-max
```

## 如何新增

1. 在本目录新增 `<agent-key>.yml`（或 `.yaml`）。
2. 保证 `modelConfig.modelKey` 在 `example/models` 中可解析。
3. 若需要目录化布局，可一次性生成到 `~/.zenmind/agents/<agent-key>/`。

## 附带示例

- `demoScheduleManager`：计划任务管理示例（REACT + `_bash_`），用于维护 `schedules/*.yml`，按“渐进式披露阅读”读取文件头，并按结构化 schedule 契约写入 `environment.zoneId` 和 `query` 支持字段（如 `message/chatId/hidden`）。
- `demoImageGenerator`：图像生成示例（REACT + `image.models.list/image.generate/image.edit/image.import`），用于通过 `mcp-server-imagine` 在当前 chat 目录中发现、生成、导入和编辑图片。

## 与外层目录关系

- 源：`example/agents/`
- 推荐目标：`~/.zenmind/agents/<agent-key>/`
- 目录化结构：`agent.yml` + 可选 `SOUL.md` / `AGENTS.md` / `AGENTS.<stage>.md` / `memory/` / `experiences/` / `skills/` / `tools/`
- 生成策略：同一个 `key` 若目标目录已存在则直接失败，避免覆盖用户自定义内容
- 目录化脚手架中的占位 `memory/*.md`、`experiences/*.md` 默认为空；占位 skill/tool 会带 `scaffold: true`，运行时会自动忽略
