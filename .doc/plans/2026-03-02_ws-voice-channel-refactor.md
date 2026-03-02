# WS 语音长连接改造（TTS PCM + ASR 占位）

## 1. 背景与目标
- 在现有 `/api/ap/query` SSE 主链路之外，新增可开关的语音 WS 长连接能力。
- 首版目标：
  - 落地 `tts.start/chunk/commit/stop` 的低延迟 PCM 回传；
  - 定义 `asr.*` 协议边界、chatId 复用规则与中断语义；
  - 保持默认关闭，避免影响现有 HTTP/SSE 生产流量。

## 2. 范围（In/Out）
### In
- 新增 `agent.voice.ws.*` 配置及默认值。
- 新增 `/api/ap/ws/voice` 单入口多命令 WS 通道。
- Bearer 握手鉴权（沿用 JWT 口径）。
- `pcm/opus` 协议字段中预留 `opus`，本版仅支持 `pcm`。
- `.doc` 同步：API、架构、后端模块、计划与 changelog。

### Out
- 不实现 ASR 识别引擎。
- 不实现 Opus 编解码。
- 不改造 `/api/ap/query` SSE 主链路。

## 3. 约束与风险
- 兼容：默认关闭（`agent.voice.ws.enabled=false`）。
- 安全：不开匿名 WS；`agent.auth.enabled=true` 时要求 Bearer。
- 风险：当前 PCM 为后端合成音占位，后续对接真实 TTS 时需替换实现。
- `[DOC-GAP]`：并发与性能目标未显式给定，当前按单机中等并发默认策略。

## 4. 方案与取舍
- 路径：`/api/ap/ws/voice`（可配置）。
- 协议：单连接承载 `tts.* + asr.*`，避免双连接复杂度。
- 打断：TTS 播放中收到 `asr.start`，先发 `tts.interrupted`，再发 `asr.not_implemented`。
- TTS：每个 `tts.chunk` 即时输出 PCM 分片，实现“立刻持续播报”。
- ASR：完整命令占位，统一返回 `NOT_IMPLEMENTED`，但边界与 chatId 语义先固化。

## 5. 任务拆解（带任务 ID）
- [x] T1 基线采集与冲突标注（含 `[DOC-GAP]`）。
- [x] T2 新增 `agent.voice.ws.*` 配置模型与样例配置。
- [x] T3 新增 Voice WS 路由与 Handler（单入口多命令）。
- [x] T4 接入 Bearer 握手鉴权与会话内 UNAUTHORIZED 兜底。
- [x] T5 落地 TTS PCM 输出链路（`start/chunk/commit/stop`）。
- [x] T6 落地 ASR 占位协议（`asr.* -> asr.not_implemented`）。
- [x] T7 落地 barge-in 语义（`tts.interrupted`）。
- [x] T8 增加测试：开关、鉴权、协议与错误码。
- [x] T9 同步 `.doc` 模块文档（API/架构/后端）。
- [x] T10 写入 changelog 归档与验证结论。

## 6. 受影响文档
- `.doc/api/SPEC.md`
- `.doc/api/AUTH.md`
- `.doc/api/modules/request.md`
- `.doc/architecture/SYSTEM.md`
- `.doc/architecture/DATA_FLOW.md`
- `.doc/architecture/TECH_STACK.md`
- `.doc/backend/MODULE_MAP.md`
- `.doc/backend/modules/security_auth.md`
- `.doc/backend/modules/orchestration.md`

## 7. 验收标准
- `enabled=false` 时 WS 路径不可用（404）。
- `enabled=true` 且无 Bearer（`agent.auth.enabled=true`）时返回 401。
- `tts.start -> tts.chunk* -> tts.commit` 输出二进制 PCM + `tts.done`。
- `codec=opus` 返回 `UNSUPPORTED_CODEC`。
- TTS 过程中 `asr.start` 触发 `tts.interrupted` + `asr.not_implemented`。
- `asr.*` 返回结构化 `NOT_IMPLEMENTED`，并保留 `chatId` 语义说明。

## 8. 回滚方案
- 配置回滚：`agent.voice.ws.enabled=false`。
- 代码回滚：撤销 `voice/ws` 模块与 `agent.voice.ws.*` 配置项。
- 文档回滚：还原本计划关联的 `.doc` 模块变更与 changelog。

## 9. 确认区
- 状态: approved
- 结论: 用户在本轮明确下达 “PLEASE IMPLEMENT THIS PLAN”，按计划完成实施。
- 确认时间: 2026-03-02

## 执行记录
- 执行状态: completed
- 偏差说明:
  - 测试环境无法绑定随机端口（沙箱限制），`RANDOM_PORT` 集成测试改为可在沙箱执行的等价测试形态（`MOCK` + Handler 单测）。

## `.doc` 模块同步记录
- T5 -> `.doc/api/SPEC.md`、`.doc/api/AUTH.md`：completed
- T6 -> `.doc/api/modules/request.md`：completed
- T7 -> `.doc/architecture/SYSTEM.md`、`.doc/architecture/DATA_FLOW.md`、`.doc/architecture/TECH_STACK.md`：completed
- T8 -> `.doc/backend/MODULE_MAP.md`、`.doc/backend/modules/security_auth.md`、`.doc/backend/modules/orchestration.md`：completed

## [DOC-GAP] 记录
1. 技能模板建议 `/.doc/changelogs`，仓库实际目录为 `/.doc/changelog`，本次按仓库事实落地。
2. 并发容量、端到端时延 SLA 未给定，当前按默认参数实现并在后续压测后再收敛。
