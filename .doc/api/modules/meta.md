# Meta 模块 API（agent / team / skill / tool）

## 统一说明
- 本模块聚合元数据查询接口，不承载执行态流式过程。
- 无全局分页参数；如需过滤，仅使用各接口定义的 `tag/kind`。
- 单项接口 `/api/ap/agent`、`/api/ap/skill`、`/api/ap/tool` 已下线（HTTP 404）。

## 1. Agent 接口
### `GET /api/ap/agents`
返回 Agent 摘要列表（支持 `tag` 模糊过滤）。

Query 参数：
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tag` | string | 否 | 匹配 `id/description/role/tools/skills` |

成功 `data=AgentSummary[]`：
- `key`, `name`, `icon`, `description`, `role`, `meta`
- `meta` 常见字段：`model`, `mode`, `tools[]`, `skills[]`

## 2. Team 接口
### `GET /api/ap/teams`
返回 Team 摘要列表。

成功 `data=TeamSummary[]`：
- `teamId`: 12 位十六进制短 UUID（来源：`teams/<teamId>.json` 文件名）
- `name`: team 名称
- `icon`: 按 `agentKeys` 顺序取首个有效 agent 的 icon；无有效成员时为 `null`
- `agentKeys`: team 成员 agent key 列表
- `meta.invalidAgentKeys`: 无效成员 key 列表（保留原始 `agentKeys`，不自动过滤）

## 3. Skill 接口
### `GET /api/ap/skills`
查询 skill 摘要列表。

Query 参数：
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tag` | string | 否 | 匹配 `id/name/description/prompt` |

成功 `data=SkillSummary[]`：
- `key`, `name`, `description`, `meta.promptTruncated`

## 4. Tool 接口
### `GET /api/ap/tools`
查询工具列表，支持 `kind/tag` 过滤。

Query 参数：
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tag` | string | 否 | 匹配名称、描述、hint、toolType 等 |
| `kind` | string | 否 | `backend` / `frontend` / `action` |

成功 `data=ToolSummary[]`：
- `key`, `name`, `description`
- `meta`: `kind`, `toolType`, `toolApi`, `viewportKey`, `strict`

失败场景：
| 场景 | HTTP | code |
|---|---|---|
| `kind` 非法 | 400 | 400 |
