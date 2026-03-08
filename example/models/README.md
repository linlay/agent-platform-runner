# models 示例说明

## 用途

该目录存放示例模型定义，供 agent 的 `modelKey` 引用。

## 命名规范

- 文件后缀：`.json`
- 文件命名：`<model-key>.json`
- `key` 建议与文件名一致

## 最小示例

```json
{
  "key": "bailian-qwen3-max",
  "provider": "bailian",
  "protocol": "OPENAI",
  "modelId": "qwen-max"
}
```

## 如何新增

1. 新增 `<model-key>.json`。
2. 必填字段：`key/provider/protocol/modelId`。
3. `protocol` 表示线协议，当前推荐使用 `OPENAI`；provider 的 endpoint 差异通过 `configs/providers/<provider>.yml` 中的 `agent.providers.<provider>.protocols.<PROTOCOL>.endpoint-path` 配置。
4. 执行示例安装脚本同步到外层 `models/`。

## 与外层目录关系

- 源：`example/models/`
- 目标：项目根目录 `models/`
- 策略：覆盖同名文件，保留额外文件
