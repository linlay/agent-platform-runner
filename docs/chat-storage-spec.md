# Chat Storage V3.1（JSONL）

以下为 V3 格式基础 + V3.1 增量改进的完整规范。

- 存储文件：`chats/{chatId}.jsonl`，JSONL 格式，**一行一个 step**，逐步增量写入。
- 行类型通过 `_type` 字段区分：
  - `"query"`：用户原始请求行。必带 `chatId`、`runId`、`updatedAt`、`query`。
  - `"step"`：一个执行步骤行。必带 `chatId`、`runId`、`_stage`、`_seq`、`updatedAt`、`messages`；可选 `taskId`、`system`、`plan`、`artifacts`。
- `_stage` 标识步骤阶段：`"oneshot"` / `"react"` / `"plan"` / `"execute"` / `"summary"`。
- `_seq` 全局递增序号，标识 run 内的步骤顺序。
- `query` 保存完整 query 结构（`requestId/chatId/agentKey/role/message/references/params/scene/stream`）。
- `system` 快照规则：每个 run 的第一个 step 写入；stage 切换且 system 变化时再写入；后续 step 如果 system 未变化则省略。
- `messages` 采用 OpenAI 风格：
  - `role=user`：`content[]`（text parts）+ `ts`
  - `role=assistant`：三种快照形态之一：`content[]` / `reasoning_content[]` / `tool_calls[]`
  - `role=tool`：`name` + `tool_call_id` + `content[]` + `ts`
- assistant / tool 扩展字段支持：`_reasoningId`、`_contentId`、`_msgId`、`_toolId`、`_actionId`、`_timing`、`_usage`。
- action / tool 判定：通过 `chat.storage.action-tools` 白名单；命中写 `_actionId`，否则写 `_toolId`。
- chat storage 回放约束：`reasoning_content` **不回传**给下一轮模型上下文。
- 滑动窗口：`k=20` 单位仍然是 **run**；`trimToWindow` 按 `runId` 分组，保留最近 `k` 个 run 的所有行。

## V3.1 增量改进

### _msgId

- 新增 `_msgId`（格式 `m_xxxxxxxx`，8 位 hex）标识同一 LLM 响应拆分的多条 assistant 消息。
- 同一模型回复中的 reasoning、content、tool_calls 消息共享相同 `_msgId`。
- tool result 到来后，下一个 reasoning / content delta 会重新生成 `_msgId`。

### tool_calls 拆分规则

- 每条 `role=assistant` 的 `tool_calls` 数组只含 **1 个**工具调用。
- 并行多工具调用拆分为多条 assistant 消息，通过共享 `_msgId` 关联。

### _toolId / _actionId 位置

- `_toolId` 和 `_actionId` 写入 `StoredMessage` 外层（与 `_reasoningId`、`_contentId` 同级）。
- `StoredToolCall` 内层的 `_toolId` / `_actionId` 仅用于反序列化旧 V3 数据，新数据不再写入。
- 读取时先查外层，再 fallback 内层（兼容旧数据）。

### _toolId 生成规则

| 工具类型 | 生成规则 |
|----------|---------|
| backend（`type=function`） | 直接使用 LLM 原始 `tool_call_id`（如 `call_b7332997a5b1490ca7195293`） |
| frontend（`type=frontend`） | `t_` + 8 位 hex（系统生成） |
| action（`type=action`） | `a_` + 8 位 hex（系统生成） |

### ID 前缀简化

| ID 类型 | 旧前缀 | 新前缀 |
|---------|--------|--------|
| reasoningId | `reasoning_` | `r_` |
| contentId | `content_` | `c_` |
| toolId (frontend) | `tool_` | `t_` |
| actionId | `action_` | `a_` |
| msgId | (新增) | `m_` |

SSE 事件中的 reasoningId / contentId 同步使用新前缀格式：`{runId}_r_{seq}` / `{runId}_c_{seq}`。

### _usage 真实填充

- 通过 `stream_options.include_usage=true` 请求 LLM provider 返回真实 usage 数据。
- `LlmDelta` record 新增 `Map<String, Object> usage` 字段，SSE parser 解析最后一个 chunk 的 usage。
- usage 通过管道穿透：`LlmDelta` → `AgentDelta` → `StepAccumulator.capturedUsage` → `RunMessage` → `StoredMessage._usage`。
- 不再写入 placeholder null 值；当 LLM 未返回 usage 时 `_usage` 仍使用默认占位结构。
