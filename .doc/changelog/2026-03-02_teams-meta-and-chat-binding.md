# 2026-03-02_teams-meta-and-chat-binding

## 变更类型
- refactor
- breaking-change
- docs

## 改动摘要
- 新增 team 元数据体系：`teams/`、`src/main/resources/teams/`、`TeamRegistryService`、`GET /api/ap/teams`。
- 下线元数据单项接口：`GET /api/ap/agent`、`GET /api/ap/skill`、`GET /api/ap/tool`。
- 会话索引扩展：`CHATS` 新增 `TEAM_ID_`，`GET /api/ap/chats` 新增 `teamId`。
- 运行时同步与热加载扩展到 teams 目录。

## 计划关联
- plan: `.doc/plans/2026-03-02_teams-meta-and-chat-binding.md`
- tasks: T1, T2, T3, T4, T5, T6, T7

## 影响评估
- 客户端需要从单项查询接口切换为全量列表同步模式。
- 旧会话数据保持可读；新字段 `teamId` 在历史数据中默认 `null`。

## 验证结果
- 新增/更新测试覆盖 teams 接口、单项接口下线、`TEAM_ID_` 增量迁移、目录同步与监听。
- 文档与 README 已同步契约。

## [DOC-GAP] 决策记录
1. `/api/ap/team` 未在现有实现中存在，按“不新增单项接口”落地。
2. `.doc/plans` 目录缺失已补齐。
3. `query` 维持 `agentKey` 必填，`TEAM_ID_` 作为后续能力预留。
