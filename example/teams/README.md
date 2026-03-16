# teams 示例说明

## 用途

该目录存放示例 team 定义，文件名即 teamId（12 位十六进制）。

## 命名规范

- 文件后缀：`.yml`、`.yaml`
- 文件命名：`<team-id>.yml`
- `team-id`：12 位小写十六进制，例如 `a1b2c3d4e5f6`

## 最小示例

```yaml
name: Default Team
defaultAgentKey: demoModeReact
agentKeys:
  - demoModeReact
```

## 如何新增

1. 新建 `<team-id>.yml`。
2. 在 `agentKeys` 中填写已存在的 agent key。
3. 可选设置 `defaultAgentKey`，用于 team 自身的默认执行语义；schedule 仍需显式填写 `agentKey`。
4. 执行示例安装脚本同步到外层 `teams/`。

## 与外层目录关系

- 源：`example/teams/`
- 目标：项目根目录 `teams/`
- 策略：覆盖同名文件，保留额外文件
