# 2026-03-02_action-memory-identity-fix

## 变更类型
- fix
- docs

## 改动摘要
- 修正 `ChatWindowMemoryStore` 的 action 身份落盘判定：新增 run 级 `system.tools(type=action)` 识别来源。
- 统一 `_actionId/_toolId` 落盘规则：均优先复用原始 `tool_call_id`。
- 补充测试覆盖：
  - `tool_call.type=function` + `system.tools(type=action)` 仍落 `_actionId`
  - 显式 `tool_call.type=action` 优先

## 影响评估
- 不改变对外 API 字段。
- `rawMessages` 中 action/tool 身份字段更稳定，`/api/ap/chat` 回放语义与流式链路更一致。

## 验证结果
- `mvn -Dtest=ChatWindowMemoryStoreTest,ChatRecordStoreTest test`
- 结果：`BUILD SUCCESS`，相关测试通过。

## 文档同步
- `.doc/backend/modules/memory_chat.md`
- `.doc/backend/modules/tool_runtime.md`
- `.doc/api/modules/chat.md`
- `.doc/GUIDE.md`
