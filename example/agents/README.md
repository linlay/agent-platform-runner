# agents 示例说明

## 用途

该目录存放示例智能体 JSON 定义文件，文件名建议与 `key` 保持一致。

## 命名规范

- 文件后缀：`.json`
- 文件命名：`<agent-key>.json`
- `key` 唯一，建议使用小写字母/数字/下划线组合

## 最小示例

```json
{
  "key": "demo_simple",
  "name": "Demo Simple",
  "description": "demo agent",
  "role": "示例角色",
  "mode": "ONESHOT",
  "modelConfig": {
    "modelKey": "bailian-qwen3-max"
  }
}
```

## 如何新增

1. 在本目录新增 `<agent-key>.json`。
2. 保证 `modelConfig.modelKey` 在 `example/models` 中可解析。
3. 运行示例安装脚本同步到外层 `agents/`。

## 与外层目录关系

- 源：`example/agents/`
- 目标：项目根目录 `agents/`
- 策略：覆盖同名文件，保留额外文件
