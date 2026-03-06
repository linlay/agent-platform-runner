# 工具运行时（tool_runtime）

## 关键类
- `ToolRegistry`
- `ToolFileRegistryService`
- `ToolExecutionService`
- `FrontendSubmitCoordinator`

## 工具来源
1. Java 内置工具（`BaseTool` 实现）
2. 外置工具文件（`tools/*.backend|*.frontend|*.action`）

## 冲突规则
- 同名 capability 冲突时，两者都跳过（防止二义性）。
- backend capability 若无同名 Java 实现，会被跳过并告警。

## 调用类型
- backend: `toolType=function`
- frontend: `toolType=frontend`（并补充 `toolKey/toolTimeout`）
- action: `toolType=action`

## 记忆落盘关联
- `ChatWindowMemoryStore` 在写入 step JSONL 时会按 action/tool 身份写 `_actionId` 或 `_toolId`。
- action 判定除 `toolCallType` 与 `memory.chat.action-tools` 外，还会参考当前 step 的 `system.tools(type=action)`。
- `_actionId/_toolId` 均优先复用原始 `tool_call_id`，便于与流式事件和回放事件对齐。

## Frontend submit 协议
- 执行时注册 pending key: `runId#toolId`
- `/submit` 命中后完成 future 并返回 `accepted`
- 超时抛 `TimeoutException`，转为运行失败提示
