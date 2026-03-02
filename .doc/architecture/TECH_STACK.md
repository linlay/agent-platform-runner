# 技术栈与版本

## 后端框架
- Java: `21`
- Spring Boot: `3.3.8`
- Spring AI: `1.0.0`
- Web: `Spring WebFlux (Reactor)`
- Voice 通道: `Spring WebFlux WebSocket`
- JSON: `Jackson`
- 构建: `Maven`

## 协议与模型
- 模型协议枚举：`OPENAI`、`NEWAPI_OPENAI_COMPATIBLE`、`ANTHROPIC`
- 当前实现状态：
  - `OPENAI`：原生 SSE + ChatClient 路径已实现。
  - `NEWAPI_OPENAI_COMPATIBLE`：基于 OpenAI 兼容解析，endpoint 可配置。
  - `ANTHROPIC`：流式接口占位；`completeText` 明确未实现。

## 存储
- 会话索引：SQLite（`CHATS` 表，默认 `chats.db`）
- 聊天记忆：JSONL（`chats/{chatId}.json`）

## 运行时资源目录
- `agents/`（Agent JSON）
- `models/`（模型定义）
- `tools/`（.backend/.frontend/.action）
- `skills/`（`<id>/SKILL.md` + scripts）
- `viewports/`（`.html` / `.qlc`）
- `data/`（静态文件服务目录）

## 关键依赖模块
- SSE 管线：`stream/service/*`
- LLM 网关：`service/LlmService*`
- 编排：`agent/mode/*`, `agent/runtime/*`
- 安全：`security/*`
- Voice WS：`voice/ws/*`

## 日志与可观测
- API 请求摘要日志：`logging.agent.request.*`
- 认证与异常日志：`logging.agent.auth.enabled`、`logging.agent.exception.enabled`
- Tool/Action/Viewport/SSE 日志：`logging.agent.tool.*`、`logging.agent.action.*`、`logging.agent.viewport.enabled`、`logging.agent.sse.*`
- LLM 交互日志：`logging.agent.llm.interaction.enabled=true`（默认）
- 敏感字段脱敏：`logging.agent.llm.interaction.mask-sensitive=true`（默认）
- 关键日志分类：`ApiRequestLoggingWebFilter`, `ApiJwtAuthWebFilter`, `LlmService`, `OpenAiCompatibleSseClient`, `LlmCallLogger`
