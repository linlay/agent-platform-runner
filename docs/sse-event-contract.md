# SSE 事件契约

## 1. 基础字段（所有 SSE 事件）

- 必带字段：`seq`, `type`, `timestamp`
- 不再输出：`rawEvent`

## 2. 输入与会话事件

- `request.query`：`requestId`, `chatId`, `role`, `message`, `agentKey?`（未绑定 chat 首个 query 必填）, `references?`（`Reference` 支持 `sandboxPath`）, `params?`, `scene?`, `stream?`
- `request.upload`：`requestId`, `chatId?`, `upload:{type,name,sizeBytes,mimeType,sha256?}`
- `request.submit`：`requestId`, `chatId`, `runId`, `toolId`, `payload`, `viewId?`
- `request.steer`：`requestId?`, `chatId`, `runId`, `steerId`, `message`, `role=user`
- `chat.start`：`chatId`, `chatName?`（仅该 chat 首次 run 发送一次）

## 3. 计划、运行与任务事件

- `plan.create`：`planId`, `chatId`, `plan`
- `plan.update`：`planId`, `chatId`, `plan`（总是带 `chatId`）
- `run.start`：`runId`, `chatId`, `agentKey`
- `run.complete`：`runId`, `finishReason?`（仅成功完成）
- `run.cancel`：`runId`
- `run.error`：`runId`, `error`
  - `error`：`code`, `message`, `scope`, `category`, `diagnostics?`
- `task.*`：仅在"已有 plan 且显式 `task.start` 输入"时出现；不自动创建 task

## 4. 推理与内容事件

- `reasoning.start`：`reasoningId`, `runId`, `taskId?`
- `reasoning.delta`：`reasoningId`, `delta`
- `reasoning.end`：`reasoningId`
- `reasoning.snapshot`：`reasoningId`, `runId`, `text`, `taskId?`
- `content.start`：`contentId`, `runId`, `taskId?`
- `content.delta`：`contentId`, `delta`
- `content.end`：`contentId`
- `content.snapshot`：`contentId`, `runId`, `text`, `taskId?`

## 5. 工具、产物与动作事件

- `tool.start`：`toolId`, `runId`, `taskId?`, `toolName?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`
- `tool.args`：`toolId`, `delta`, `chunkIndex?`（字段名保持 `delta`，不使用 `args`）
- `tool.end`：`toolId`
- `tool.result`：`toolId`, `result`
- `artifact.publish`：`artifactId`, `chatId`, `runId`, `artifact`（独立事件；`artifact` 仅含 `type/name/mimeType/sizeBytes/url/sha256`；一次 `_artifact_publish_` 调用里有几个产物，就会发几条 `artifact.publish`）
- `tool.snapshot`：`toolId`, `runId`, `toolName?`, `taskId?`, `toolType?`, `toolLabel?`, `toolDescription?`, `viewportKey?`, `arguments?`
- `action.start`：`actionId`, `runId`, `taskId?`, `actionName?`, `description?`
- `action.args`：`actionId`, `delta`
- `action.end`：`actionId`
- `action.param`：`actionId`, `param`
- `action.result`：`actionId`, `result`
- `action.snapshot`：`actionId`, `runId`, `actionName?`, `taskId?`, `description?`, `arguments?`

## 6. 补充行为约束

- 无活跃 task 出错时：只发 `run.error`（不补 `task.fail`）。
- plain 模式（当前无 plan）不应出现 `task.*`，叶子事件直接归属 `run`。
- `GET /api/chat` 历史事件需与新规则对齐；历史使用 `*.snapshot` 替代 `start/end/delta/args` 细粒度流事件，并保留 `tool.result` / `action.result`；`plan.update` / `artifact.publish` 提升为顶层状态 `data.plan` / `data.artifact`，其中 `data.artifact.items[]` 为聚合视图。
- 历史里每个 run 都保留一个终态事件：成功为 `run.complete`，失败为 `run.error`，取消为 `run.cancel`；`chat.start` 仅首次一次。
- `/api/query` 在流式输出结束时追加传输层终止帧 `data:[DONE]`；该 sentinel 不属于 Event Model v2 业务事件，也不写入 chat 历史事件。
- `RenderQueue` 只影响传输 flush 行为，不改变上述业务事件类型与字段契约。
- `_artifact_publish_` 是隐藏的内置后端工具：接收 `artifacts[]`，每项为 `path/name?/description?`；成功时 `tool.result` 可返回 `{ok,artifacts:[{artifactId,artifact},...]}`。如果一次调用里有多个产物，实时流中就会出现多条 `artifact.publish`。
