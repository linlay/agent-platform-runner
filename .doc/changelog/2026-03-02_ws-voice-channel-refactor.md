# 2026-03-02_ws-voice-channel-refactor

## 变更类型
- refactor
- docs

## 改动摘要
- 新增 Voice WS 可配置能力（默认关闭）：
  - `agent.voice.ws.enabled/path/codecs/max-session-seconds/default-sample-rate/default-channels`
  - 更新 `application.yml` 与 `application.example.yml`
- 新增后端模块：
  - `config/VoiceWsProperties`
  - `voice/ws/VoiceWebSocketConfiguration`
  - `voice/ws/VoiceWebSocketHandler`
  - `voice/ws/VoiceWsAuthenticationService`
  - `voice/ws/VoicePcmSynthesizer`
  - `voice/ws/SyntheticVoicePcmSynthesizer`
- 协议能力：
  - 实现 `tts.start/chunk/commit/stop`（`chunk` 即时回 PCM 二进制帧）
  - 实现 `asr.start|chunk|commit|stop` 占位返回 `asr.not_implemented`
  - 实现 barge-in：TTS 中收到 `asr.start` -> `tts.interrupted`
  - `opus` 预留但返回 `UNSUPPORTED_CODEC`
- 测试新增：
  - `VoiceWebSocketToggleIntegrationTest`
  - `VoiceWebSocketAuthIntegrationTest`
  - `VoiceWebSocketHandlerTest`

## 计划关联
- plan: `.doc/plans/2026-03-02_ws-voice-channel-refactor.md`
- tasks: T1, T2, T3, T4, T5, T6, T7, T8, T9, T10

## 文档同步结果
- paths:
  - `.doc/api/SPEC.md`
  - `.doc/api/AUTH.md`
  - `.doc/api/modules/request.md`
  - `.doc/architecture/SYSTEM.md`
  - `.doc/architecture/DATA_FLOW.md`
  - `.doc/architecture/TECH_STACK.md`
  - `.doc/backend/MODULE_MAP.md`
  - `.doc/backend/modules/security_auth.md`
  - `.doc/backend/modules/orchestration.md`

## 影响评估
- 运行兼容性：默认关闭，不影响现有 `/api/ap/query` SSE 能力。
- 安全影响：`agent.auth.enabled=true` 时 WS 复用 Bearer 鉴权口径。
- 协议影响：新增 `/api/ap/ws/voice` 与 `tts/asr` 命令空间。

## 验证结果
- 定向测试通过：
  - `mvn -q -Dtest=VoiceWebSocketToggleIntegrationTest,VoiceWebSocketAuthIntegrationTest,VoiceWebSocketHandlerTest test`
- 关键验收点：
  - `enabled=false` -> WS 路径 404
  - 无 Bearer -> 401
  - `tts.start/chunk/commit` -> PCM 二进制输出 + `tts.done`
  - `codec=opus` -> `UNSUPPORTED_CODEC`
  - `asr.start` 抢占 -> `tts.interrupted` + `asr.not_implemented`

## [DOC-GAP] 决策记录
- 目录命名差异：使用仓库既有 `/.doc/changelog`，不新建 `/.doc/changelogs`。
- 沙箱无法绑定随机端口：将依赖真实端口的 WS 集成测试改为等价可执行测试（`MOCK` + Handler 单测）。
