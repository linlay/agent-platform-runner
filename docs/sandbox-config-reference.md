# sandboxConfig 配置参考

`sandboxConfig` 用于声明 Agent 的 Container Hub 沙箱参数，包括 `environmentId`、`level` 和 `extraMounts`。

## 完整示例

```yaml
sandboxConfig:
  environmentId: shell
  level: agent        # run / agent / global；为空时使用全局 default-sandbox-level
  extraMounts:
    - platform: models
      mode: ro
    - platform: tools
      mode: rw
    - platform: chats
      mode: ro
    - platform: owner
      mode: rw
    - source: /abs/host/path
      destination: /datasets
      mode: ro
    - destination: /skills
      mode: rw
```

## extraMounts 平台简写

| `platform` | 容器路径 |
|-----------|----------|
| `models` | `/models` |
| `tools` | `/tools` |
| `agents` | `/agents` |
| `viewports` | `/viewports` |
| `viewport-servers` | `/viewport-servers` |
| `teams` | `/teams` |
| `schedules` | `/schedules` |
| `mcp-servers` | `/mcp-servers` |
| `providers` | `/providers` |
| `chats` | `/chats` |
| `owner` | `/owner` |

## 挂载原则

- 默认最小集：默认只挂载 `/workspace`、`/root`、`/skills`、`/pan`、`/agent`，不再默认暴露全量平台配置目录。
- agent 就近原则：当前 agent 若采用目录化布局，默认挂载其自身目录到 `/agent`；扁平 YAML agent 不强制创建该挂载。
- 默认安全模式：`/skills` 与 `/agent` 默认只读；`/workspace`、`/root`、`/pan` 默认读写。
- 按需显式原则：`/models`、`/tools`、`/agents`、`/viewports`、`/teams`、`/schedules`、`/mcp-servers`、`/providers`、`/chats`、`/owner` 仅能通过 `sandboxConfig.extraMounts` 显式恢复。
- 模式显式原则：所有按需平台挂载和自定义挂载都必须显式声明 `mode: ro|rw`。
- 基础挂载覆盖原则：若只想修改 `/workspace`、`/root`、`/skills`、`/pan`、`/agent` 的模式，可在 `extraMounts` 中只写 `destination + mode` 覆盖默认模式，不新增第二个挂载。
- 最小暴露原则：agent 只应声明完成任务所必需的额外挂载，避免把无关目录带入沙箱。
- 安全优先原则：custom mount 必须满足“源目录存在、目标路径为绝对路径、目标路径不冲突”；不满足时直接 fail-fast。
- 敏感目录显式授权：`providers` 属于敏感挂载，即使在 `extraMounts` 中声明，也必须先有全局 `agent.providers.external-dir` 目录。

## 约束规则

- `platform` 未知时仅 warn 并跳过。
- `platform` 挂载必须显式声明 `mode: ro|rw`。
- custom mount 必须提供 `source + destination + mode`。
- `platform: owner` 绑定的是 owner 目录；该目录内的正式 owner 文档位于 `owner/OWNER.md`。
- 默认基础挂载 `/workspace`、`/root`、`/skills`、`/pan`、`/agent` 可通过 `destination + mode` 覆盖默认模式。
- custom mount 的 `destination` 必须是绝对路径，且不能与已有挂载冲突。
- custom mount 的 `source` 必须是已存在目录。
- `platform: providers` 只有在 `mounts.providers-dir` 已显式配置时才可用。

## 使用建议

- 普通 agent 优先保持默认挂载，仅在确实需要访问平台目录时才声明 `extraMounts`。
- 若只读即可完成任务，优先使用 `mode: ro`。
- 只有在需要写回平台资产时，才对 `/tools`、`/models`、`/agents` 等额外挂载开放 `rw`。
- 对 `providers`、`/owner`、`/chats` 这类敏感路径，建议先明确用途再暴露。
