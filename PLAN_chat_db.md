## SQLite 聊天索引重构 + Agent 维度会话列表（不兼容升级）

### Summary
本次按你最新要求做一次不兼容升级：
1. 不再兼容 `./chats/_chats.jsonl`，索引改为 SQLite，数据库文件名固定 `chats.db`。  
2. `GET /api/ap/chats` 继续保持前端字段 `firstAgentKey/firstAgentName`，后端从 SQLite 的 `agent` 字段做转换。  
3. 新增 Agent 维度列表接口，仅保留一个排序参数，默认按最新 chat 时间倒序。  
4. 采用多表方案并加入“轻量通知队列”语义，支持未读统计与 ACK（更接近聊天软件模型）。  
5. chat 详细内容仍保存在 `./chats/{chatId}.json`，不改。

### Public API / 类型变更

### 1) 保留并改造 `GET /api/ap/chats`
返回仍是 chat 维度列表，字段保持兼容：
- `chatId`
- `chatName`
- `firstAgentKey`（由 `AGENT_KEY_` 转换）
- `firstAgentName`（由 `AGENT_NAME_` 转换）
- `createdAt`
- `updatedAt`
- `agentAvatar`（新增，可选；来自 `AGENT_AVATAR_`）

排序：`updatedAt DESC`（保持当前行为）。

### 2) 新增 `GET /api/ap/agent-chats`
用途：Agent（好友）维度列表。  
Query 参数：
- `sort`（可选，默认 `LATEST_CHAT_TIME_DESC`）

支持值：
- `LATEST_CHAT_TIME_DESC`（默认）
- `UNREAD_CHAT_COUNT_DESC`
- `AGENT_NAME_ASC`

不提供 `limit/offset`。

返回类型：`ApiResponse<List<AgentChatSummaryResponse>>`  
`AgentChatSummaryResponse` 字段：
- `agentKey`
- `agentName`
- `avatar`
- `latestChatId`
- `latestChatName`
- `latestChatContent`
- `latestChatTime`
- `unreadChatCount`

### 3) 新增 `POST /api/ap/agent-reads`
用途：按 Agent 执行已读 ACK（你选了单独接口）。  
请求体：
- `agentKey`

返回：
- `agentKey`
- `ackedEvents`
- `ackedChats`
- `unreadChatCount`（ACK 后值，预期 0）

### 4) Agent 绑定规则（关键行为）
一 chat 一 agent：
- chat 首次创建绑定 `agentKey`。  
- 后续 query 带同一 `chatId` 时，若请求又带了别的 `agentKey`，忽略请求值，使用已绑定 agent 执行。

## SQLite 设计（多表 + 轻量队列）

数据库文件：`chats.db`  
命名约束：表字段全大写、下划线风格、字段名以 `_` 结尾。

```sql
CREATE TABLE IF NOT EXISTS CHAT_INDEX_ (
  CHAT_ID_ TEXT PRIMARY KEY,
  CHAT_NAME_ TEXT NOT NULL,
  AGENT_KEY_ TEXT NOT NULL,
  AGENT_NAME_ TEXT NOT NULL,
  AGENT_AVATAR_ TEXT,
  CREATED_AT_ INTEGER NOT NULL,
  UPDATED_AT_ INTEGER NOT NULL,
  LAST_CHAT_CONTENT_ TEXT NOT NULL DEFAULT '',
  LAST_CHAT_TIME_ INTEGER NOT NULL,
  LAST_RUN_ID_ TEXT
);

CREATE INDEX IF NOT EXISTS IDX_CHAT_INDEX_UPDATED_AT_
  ON CHAT_INDEX_(UPDATED_AT_ DESC);

CREATE INDEX IF NOT EXISTS IDX_CHAT_INDEX_AGENT_KEY_UPDATED_AT_
  ON CHAT_INDEX_(AGENT_KEY_, UPDATED_AT_ DESC);

CREATE TABLE IF NOT EXISTS CHAT_NOTIFY_QUEUE_ (
  EVENT_ID_ INTEGER PRIMARY KEY AUTOINCREMENT,
  AGENT_KEY_ TEXT NOT NULL,
  CHAT_ID_ TEXT NOT NULL,
  RUN_ID_ TEXT NOT NULL,
  EVENT_KIND_ TEXT NOT NULL,
  STATE_ TEXT NOT NULL,
  ENQUEUE_AT_ INTEGER NOT NULL,
  ACK_AT_ INTEGER,
  PAYLOAD_JSON_ TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS UQ_CHAT_NOTIFY_QUEUE_RUN_KIND_
  ON CHAT_NOTIFY_QUEUE_(RUN_ID_, EVENT_KIND_);

CREATE INDEX IF NOT EXISTS IDX_CHAT_NOTIFY_QUEUE_AGENT_STATE_EVENT_
  ON CHAT_NOTIFY_QUEUE_(AGENT_KEY_, STATE_, EVENT_ID_);

CREATE TABLE IF NOT EXISTS AGENT_DIALOG_INDEX_ (
  AGENT_KEY_ TEXT PRIMARY KEY,
  AGENT_NAME_ TEXT NOT NULL,
  AGENT_AVATAR_ TEXT,
  LATEST_CHAT_ID_ TEXT,
  LATEST_CHAT_NAME_ TEXT,
  LATEST_CHAT_CONTENT_ TEXT NOT NULL DEFAULT '',
  LATEST_CHAT_TIME_ INTEGER NOT NULL DEFAULT 0,
  UNREAD_CHAT_COUNT_ INTEGER NOT NULL DEFAULT 0,
  UPDATED_AT_ INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS IDX_AGENT_DIALOG_INDEX_LATEST_CHAT_TIME_
  ON AGENT_DIALOG_INDEX_(LATEST_CHAT_TIME_ DESC);
```

### 队列语义
- 每次 run 完成后，写入 `CHAT_NOTIFY_QUEUE_` 一条 `EVENT_KIND_='RUN_COMPLETED'`、`STATE_='PENDING'` 的通知。  
- 未读 chat 数 = `PENDING` 状态下 `DISTINCT CHAT_ID_` 的数量（按 agent 聚合）。  
- `POST /api/ap/agent-reads` 把指定 agent 的 `PENDING` 批量置 `ACKED`，并刷新 `AGENT_DIALOG_INDEX_`。

## 关键实现步骤（决策完成版）

1. 依赖与配置
- 增加 SQLite JDBC 依赖（`org.xerial:sqlite-jdbc`）和 JDBC 支持。
- 新增配置项：`memory.chat.index.sqlite-file`，默认 `chats.db`。
- 启动时自动建表；若检测到 `_chats.jsonl`，仅打 warning，不迁移不读取。

2. 索引存储层重构
- 将 [ChatRecordStore.java](/Users/linlay-macmini/Project/agent-platform-runner/src/main/java/com/linlay/agentplatform/service/ChatRecordStore.java) 的 `_chats.jsonl` 读写改为 SQLite DAO。
- 保留 chat 详情读取逻辑（`./chats/{chatId}.json`）不变。

3. Query 绑定逻辑
- 在 [AgentQueryService.java](/Users/linlay-macmini/Project/agent-platform-runner/src/main/java/com/linlay/agentplatform/service/AgentQueryService.java) 中：
- 若 `chatId` 已存在于 `CHAT_INDEX_`，使用绑定 agent 覆盖请求 `agentKey`。
- `ensureChat` 改为 upsert SQLite 索引，并维护 `CHAT_INDEX_`/`AGENT_DIALOG_INDEX_`。
- run 完成事件时入队 `CHAT_NOTIFY_QUEUE_`。

4. Controller 接口
- 在 [AgentController.java](/Users/linlay-macmini/Project/agent-platform-runner/src/main/java/com/linlay/agentplatform/controller/AgentController.java) 新增：
- `GET /api/ap/agent-chats?sort=...`
- `POST /api/ap/agent-reads`
- 现有 `/api/ap/chats` 返回结构保持兼容（`firstAgent*`）并做字段转换。

5. API Model
- 新增 `AgentChatSummaryResponse`、`MarkAgentReadRequest/Response`。
- `ChatSummaryResponse` 保留 `firstAgentKey/firstAgentName`，增加可选 `agentAvatar`。

## 测试与验收场景

1. 不兼容验证
- 存在 `_chats.jsonl` 时，不参与读取；服务照常从 `chats.db` 读写。

2. `/api/ap/chats` 兼容
- 字段仍为 `firstAgentKey/firstAgentName`；值来自 SQLite `AGENT_*` 映射。

3. Agent 绑定
- 已存在 chatId + 新 agentKey 请求，实际执行 agent 仍为已绑定值。

4. Agent 列表排序
- `sort` 三种策略分别正确；默认为 `LATEST_CHAT_TIME_DESC`。

5. 未读与 ACK
- run 完成后该 agent 未读 chat 数增加。
- 调用 `/api/ap/agent-reads` 后未读清零，队列状态变 `ACKED`。

6. Chat 详情回放
- `/api/ap/chat` 仍从 `./chats/{chatId}.json` 正常返回 `events/rawMessages`，行为不回归。

## Assumptions / Defaults

1. 本次升级为明确不兼容版本，不迁移历史 `_chats.jsonl`。  
2. 未读统计为系统级（不分用户），按 Agent 维度的未读 chat 数。  
3. `chats.db` 使用相对路径，默认在服务工作目录生成。  
4. `agentAvatar` 允许为空；为空时前端自行兜底展示。  
5. 队列为轻量通知队列，不存完整消息体，完整消息仍在 `./chats/{chatId}.json`。
