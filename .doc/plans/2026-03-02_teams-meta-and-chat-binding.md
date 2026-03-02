# Teams 元数据与 Chat 绑定字段改造

## 1. 背景与目标
- 引入 team 元数据能力：新增 `teams/` 外部目录和 `src/main/resources/teams` 内置资源。
- 新增 `GET /api/ap/teams`，用于全量拉取 team 信息。
- 下线低价值单项接口：`/api/ap/agent`、`/api/ap/skill`、`/api/ap/tool`。
- 扩展 `CHATS`：新增 `TEAM_ID_`，并在 `GET /api/ap/chats` 返回 `teamId`。

## 2. 范围（In/Out）
### In
- Team 注册、热加载、资源同步。
- Meta API 改造（新增 teams，移除单项接口）。
- CHATS schema 增量迁移与响应字段扩展。
- `.doc` 与 `README` 同步更新。

### Out
- 不改 `POST /api/ap/query` 入参，仍强制 `agentKey`。
- 不实现 team 驱动运行编排。
- 不新增 `/api/ap/team` 单项接口。

## 3. 约束与风险
- 兼容约束：SQLite 仅允许增量加列，不重建历史表。
- Breaking change：元数据单项接口下线，需要客户端切换列表同步。
- 数据约束：`AGENT_KEY_` 和 `TEAM_ID_` 在服务层 one-of 校验。

## 4. 方案与取舍
- Team 文件契约采用 `teams/<teamId>.json`，`teamId` 由文件名决定，格式固定为 12 位 hex。
- `/api/ap/teams` 返回 `agentKeys` 不展开 `agents[]`，并通过 `meta.invalidAgentKeys` 暴露无效成员。
- `icon` 取首个有效成员 agent 的 icon，无有效成员时返回 `null`。

## 5. 任务拆解（带任务 ID）
- [x] T1 基线与文档目录准备：创建 `.doc/plans`，登记计划与 `[DOC-GAP]`。
- [x] T2 Team 领域模型与加载：新增 `TeamCatalogProperties`、`TeamRegistryService`、Team descriptor。
- [x] T3 运行时资源同步与热加载接入：`RuntimeResourceSyncService` / `DirectoryWatchService` 接入 teams。
- [x] T4 API 控制器改造：新增 `/api/ap/teams`，移除 `/agent` `/skill` `/tool`。
- [x] T5 Chat 索引结构扩展：`CHATS.TEAM_ID_` 与 `ChatSummaryResponse.teamId`。
- [x] T6 测试更新与补充：控制器、存储、同步、监听测试更新。
- [x] T7 文档收口：更新 `.doc` 与 `README`。

## 6. 验收标准
- `GET /api/ap/teams` 可用，支持 invalid agent 标注。
- `/api/ap/agent|skill|tool` 访问返回 404。
- `GET /api/ap/chats` 返回 `teamId`。
- `CHATS` 自动补齐 `TEAM_ID_` 列。
- 文档与实现一致。

## 7. 回滚方案
- 恢复 `/agent` `/skill` `/tool` 路由与对应测试。
- 保留 `TEAM_ID_` 列但忽略该字段（无需删列）。
- 关闭 Team registry 与 `/api/ap/teams` 暴露。

## 8. 确认区
- 状态: approved
- 结论: 用户于 2026-03-02 明确要求按该计划实施。
- 确认时间: 2026-03-02

## [DOC-GAP] 记录
1. `/api/ap/team` 在实现中不存在，本次按“不新增单项 team 接口”处理。
2. 原仓库缺少 `.doc/plans` 目录，本次补齐。
3. `query` 仍要求 `agentKey`，`TEAM_ID_` 当前属于存储预留字段。
