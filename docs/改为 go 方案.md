 Agent Platform Runner: Java → Go 全面迁移方案          

 一、项目现状总览
 指标: Java 主源文件
 数值: 233 个
 ────────────────────────────────────────
 指标: 测试文件
 数值: 90 个
 ────────────────────────────────────────
 指标: 主代码行数
 数值: 32,943 LOC
 ────────────────────────────────────────
 指标: 测试代码行数
 数值: 24,244 LOC
 ────────────────────────────────────────
 指标: 总计
 数值: 57,187 LOC
 ────────────────────────────────────────
 指标: 技术栈
 数值: Java 21, Spring Boot 3.3.8, WebFlux (Reactor), Jackson
 ────────────────────────────────────────
 指标: 关键依赖
 数值: nimbus-jose-jwt, sqlite-jdbc, jackson-dataformat-yaml
 ────────────────────────────────────────
 指标: 顶层包数量
 数值: 13 个 (agent, config, service, model, stream, tool, skill, schedule, security, memory, team, controller, util)
 ---
 二、可行性评估

 2.1 总体难度：高 (8/10)

 可行但需谨慎规划。 项目深度依赖 Java 特有范式（Reactor 响应式、sealed class 继承、Spring DI），需要在 Go
 中进行架构层面的重新设计，而非简单的语法翻译。

 2.2 核心难点分析
 ┌─────────────────────────────┬───────┬──────────────────────────────────────────────────────────────────────┐
 │            难点             │ 难度  │                                 说明                                 │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ Reactor → goroutine/channel │ ★★★★☆ │ 整个数据流基于 Flux<T> / Mono<T>，需逐一改写为 channel + context     │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ SSE 事件状态机              │ ★★★★☆ │ StreamEventAssembler 745 行，40+ 输入类型的严格状态转换              │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ Agent 执行引擎              │ ★★★★☆ │ 3 种模式 (sealed class) + OrchestratorServices 编排 + 工具执行       │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ 热重载文件监控              │ ★★★☆☆ │ DirectoryWatchService 673 行，含依赖感知级联刷新                     │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ 工具系统                    │ ★★★☆☆ │ SystemBash 601 行命令校验 + ContainerHubMountResolver 717 行挂载解析 │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ Chat Memory JSONL + SQLite  │ ★★★☆☆ │ JSONL 格式 + 滑动窗口 + SQLite 索引                                  │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ 配置系统                    │ ★★☆☆☆ │ 24 个 @ConfigurationProperties → 单一嵌套 struct                     │
 ├─────────────────────────────┼───────┼──────────────────────────────────────────────────────────────────────┤
 │ JWT 安全层                  │ ★★☆☆☆ │ Go JWT 生态成熟，标准中间件模式                                      │
 └─────────────────────────────┴───────┴──────────────────────────────────────────────────────────────────────┘
 2.3 Go 代码量预估
 ┌────────────────────────────────┬──────────┬─────────────┬───────┐
 │              模块              │ Java LOC │ Go LOC 预估 │ 倍率  │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ agent (定义、模式)             │ ~7,500   │ ~5,500      │ 0.73x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ agent.runtime                  │ ~5,200   │ ~4,000      │ 0.77x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ stream (SSE 管道)              │ ~2,800   │ ~2,200      │ 0.79x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ service (核心服务)             │ ~7,500   │ ~5,800      │ 0.77x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ tool (工具系统)                │ ~3,800   │ ~3,000      │ 0.79x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ model / api                    │ ~2,600   │ ~1,800      │ 0.69x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ config                         │ ~2,800   │ ~1,500      │ 0.54x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ controller → handler           │ ~1,200   │ ~900        │ 0.75x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ memory                         │ ~1,500   │ ~1,200      │ 0.80x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ security                       │ ~700     │ ~500        │ 0.71x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ schedule / skill / team / util │ ~2,300   │ ~1,600      │ 0.70x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ 主代码合计                     │ ~32,943  │ ~28,000     │ 0.85x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ 测试代码                       │ ~24,244  │ ~18,000     │ 0.74x │
 ├────────────────────────────────┼──────────┼─────────────┼───────┤
 │ 总计                           │ ~57,187  │ ~46,000     │ 0.80x │
 └────────────────────────────────┴──────────┴─────────────┴───────┘
 Go 代码量比 Java 少约 20%，主要因为：Record → struct 减少模板代码、无 DI 框架注解、goroutine 比 Reactor 链式调用更简洁。

 ---
 三、Go 技术栈选型
 关注点: HTTP 服务器
 Java 当前: Spring WebFlux (Netty)
 Go 选型: net/http + chi router
 选型理由: chi 轻量、兼容 stdlib http.Handler、无框架锁定
 ────────────────────────────────────────
 关注点: SSE 输出
 Java 当前: ServerSentEvent<String> + Flux
 Go 选型: 自定义 sse.Writer 包装 http.Flusher
 选型理由: SSE 协议简单，无需第三方库
 ────────────────────────────────────────
 关注点: SSE 输入 (LLM)
 Java 当前: WebClient.bodyToFlux(String.class)
 Go 选型: bufio.Scanner + http.Response.Body
 选型理由: ~80 行代码替代整个响应式链
 ────────────────────────────────────────
 关注点: JSON
 Java 当前: Jackson
 Go 选型: encoding/json stdlib
 选型理由: 标准库满足所有场景，json.RawMessage 替代 JsonNode
 ────────────────────────────────────────
 关注点: YAML
 Java 当前: jackson-dataformat-yaml
 Go 选型: gopkg.in/yaml.v3
 选型理由: Go YAML 标准库
 ────────────────────────────────────────
 关注点: JWT
 Java 当前: nimbus-jose-jwt
 Go 选型: github.com/golang-jwt/jwt/v5
 选型理由: Go 生态最广泛使用的 JWT 库
 ────────────────────────────────────────
 关注点: JWKS
 Java 当前: nimbus-jose-jwt
 Go 选型: github.com/MicahParks/keyfunc/v3
 选型理由: 在 golang-jwt 之上提供 JWKS 获取 + 缓存
 ────────────────────────────────────────
 关注点: SQLite
 Java 当前: sqlite-jdbc
 Go 选型: modernc.org/sqlite
 选型理由: 纯 Go 实现，无 CGo 依赖，简化交叉编译和 Docker 构建
 ────────────────────────────────────────
 关注点: 文件监控
 Java 当前: java.nio.file.WatchService
 Go 选型: github.com/fsnotify/fsnotify
 选型理由: Go 标准文件监控库，跨平台
 ────────────────────────────────────────
 关注点: 配置
 Java 当前: @ConfigurationProperties + application.yml
 Go 选型: github.com/knadh/koanf/v2
 选型理由: 支持 YAML + 环境变量 + 默认值，比 viper 更组合友好，无全局状态
 ────────────────────────────────────────
 关注点: 定时任务
 Java 当前: Spring CronTrigger
 Go 选型: github.com/robfig/cron/v3
 选型理由: Go 事实标准 cron 库
 ────────────────────────────────────────
 关注点: 并发
 Java 当前: Project Reactor (Flux/Mono/Sinks)
 Go 选型: goroutine + channel + context.Context
 选型理由: Go 原生并发是 Reactor 的天然替代
 ────────────────────────────────────────
 关注点: HTTP 客户端
 Java 当前: WebClient (reactor-netty)
 Go 选型: net/http stdlib
 选型理由: 配合 context.Context 实现取消
 ────────────────────────────────────────
 关注点: HMAC
 Java 当前: Java crypto
 Go 选型: crypto/hmac + crypto/sha256 stdlib
 选型理由: 标准库满足
 ────────────────────────────────────────
 关注点: 日志
 Java 当前: SLF4J + Logback
 Go 选型: log/slog (Go 1.21+)
 选型理由: 标准库结构化日志
 ────────────────────────────────────────
 关注点: 参数校验
 Java 当前: Jakarta Validation
 Go 选型: github.com/go-playground/validator/v10
 选型理由: 请求校验
 ────────────────────────────────────────
 关注点: 进程执行
 Java 当前: ProcessBuilder
 Go 选型: os/exec stdlib
 选型理由: 直接等价
 ---
 四、Go 项目结构

 agent-platform-runner-go/
   cmd/
     runner/
       main.go                              // 入口，显式依赖注入
   internal/
     agent/                                 // Agent 定义、加载、注册
       definition.go                        // AgentDefinition struct
       definition_loader.go                 // YAML 加载 (平面 + 目录布局)
       config_file.go                       // AgentConfigFile (YAML schema)
       registry.go                          // AgentRegistry
       driven_agent.go                      // DefinitionDrivenAgent
       prompt.go                            // RuntimeContextPromptService
       prompt_files.go                      // SOUL.md / AGENTS.md 加载
     agent/mode/                            // 三种执行模式
       mode.go                              // AgentMode interface + 公共嵌入
       oneshot.go                           // OneshotMode
       react.go                             // ReactMode
       planexecute.go                       // PlanExecuteMode
       orchestrator.go                      // OrchestratorServices
       accumulator.go                       // ModelTurnAccumulator
       stage_settings.go                    // StageSettings
       mode_factory.go                      // AgentModeFactory
     agent/runtime/                         // 运行时上下文与控制
       context.go                           // ExecutionContext
       run_control.go                       // RunControl (context + channels)
       input_broker.go                      // RunInputBroker
       tool_execution.go                    // ToolExecutionService
       tool_invoker.go                      // ToolInvoker / ToolInvokerRouter
       frontend_submit.go                   // FrontendSubmitCoordinator
       sandbox.go                           // ContainerHubSandboxService
       mount_resolver.go                    // ContainerHubMountResolver
       budget.go                            // Budget, RunSpec, ComputePolicy
       step_accumulator.go                  // StepAccumulator
     stream/                                // SSE 流式管道
       assembler.go                         // StreamEventAssembler (状态机)
       streamer.go                          // StreamSseStreamer
       render_queue.go                      // RenderQueue (H2A 缓冲)
       flush_writer.go                      // SseFlushWriter
       normalizer.go                        // SseEventNormalizer
       delta_mapper.go                      // AgentDeltaToStreamInputMapper
       model.go                             // StreamEvent, StreamInput, StreamEnvelope 等
     stream/adapter/
       openai_parser.go                     // OpenAiSseDeltaParser
     llm/                                   // LLM 调用层
       service.go                           // LlmService
       openai_client.go                     // OpenAI 兼容 SSE 客户端
       call_logger.go                       // LlmCallLogger
     provider/
       registry.go                          // ProviderRegistryService
       config.go                            // ProviderConfig
     model/                                 // 领域模型
       delta.go                             // AgentDelta
       request.go                           // AgentRequest
       chat_message.go                      // ChatMessage (interface 模拟 sum type)
       model_def.go                         // ModelDefinition, ModelProtocol
       model_registry.go                    // ModelRegistryService
     model/api/                             // REST 契约
       response.go                          // ApiResponse[T]
       query.go / submit.go / steer.go ...  // 各请求/响应结构
     handler/                               // HTTP 处理器 (替代 controller)
       query.go                             // POST /api/query (SSE)
       submit.go / steer.go / interrupt.go
       agents.go                            // GET /api/agents, /api/teams 等
       chat.go / data.go / viewport.go
       middleware.go                        // 日志、CORS、错误恢复
     service/                               // 核心业务服务
       query_service.go                     // AgentQueryService
       active_run.go                        // ActiveRunService
       watch_service.go                     // DirectoryWatchService
     chat/                                  // 会话存储
       record_store.go                      // ChatRecordStore
       index_repo.go                        // ChatIndexRepository (SQLite)
       snapshot_builder.go                  // ChatEventSnapshotBuilder
     memory/                                // 聊天记忆
       store.go                             // ChatWindowMemoryStore
       types.go                             // ChatMemoryTypes
       converter.go                         // StoredMessageConverter
     tool/                                  // 工具系统
       base.go                              // BaseTool interface
       registry.go / file_registry.go
       bash.go / bash_validator.go          // SystemBash + 命令校验
       container_hub_bash.go / container_hub_client.go
       datetime.go / plan_tools.go
     skill/                                 // 技能系统
       registry.go / descriptor.go
     team/
       registry.go / descriptor.go
     mcp/                                   // MCP 服务器集成
       client.go / registry.go / sync.go
       availability.go / reconnect.go
     viewport/
       registry.go / server_registry.go
     schedule/                              // 定时任务
       orchestrator.go / registry.go / dispatch.go
     security/                              // 安全层
       jwt_middleware.go                    // JWT 认证中间件
       jwks_verifier.go                     // JWKS 验证
       chat_image_token.go                  // 图片令牌
     config/                                // 配置
       config.go                            // 顶层 Config struct (映射所有 24 个 properties)
       loader.go                            // koanf 加载器
     util/
       strings.go / catalog_diff.go / run_id.go
   configs/                                 // 运行时配置模板 (不变)
   runtime/                                 // 运行时数据目录 (不变)
   go.mod / go.sum
   Makefile / Dockerfile

 关键设计决策：
 - 使用 internal/ 强制封装，外部不可导入
 - 不使用 DI 框架，在 cmd/runner/main.go 中显式构造注入
 - handler/ 替代 controller/（Go 命名惯例）
 - stream/ 保持独立包，因 SSE 管道是核心领域

 ---
 五、核心技术方案

 5.1 Reactor 响应式 → goroutine + channel

 Java 模式：
 Flux.<AgentDelta>create(sink -> mode.run(ctx, tools, services, sink), BUFFER)
     .map(idAssigner::assign)
     .subscribeOn(Schedulers.boundedElastic());

 Go 方案：
 func (a *DefinitionDrivenAgent) Stream(ctx context.Context, req *AgentRequest) <-chan AgentDelta {
     ch := make(chan AgentDelta, 256)
     go func() {
         defer close(ch)
         a.mode.Run(ctx, a.tools, a.services, ch) // 直接写入 channel
     }()
     return ch
 }

 关键映射关系：
 ┌───────────────────────────────┬─────────────────────────────────────────┬──────────────────┐
 │        Java (Reactor)         │                   Go                    │       说明       │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ FluxSink<T> sink              │ chan<- T                                │ 写端 channel     │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ sink.next(delta)              │ ch <- delta (配合 select on ctx.Done()) │ 带取消保护的写入 │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ sink.isCancelled()            │ ctx.Err() != nil                        │ 检查取消状态     │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ Flux.create(..., BUFFER)      │ make(chan T, 256)                       │ 有缓冲 channel   │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ subscribeOn(boundedElastic)   │ go func() { ... }()                     │ goroutine        │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ .takeUntilOther(cancelSignal) │ select { case <-ctx.Done(): return }    │ context 取消     │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ .timeout(duration)            │ context.WithTimeout(ctx, d)             │ 超时控制         │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ Sinks.One<Void> cancelSink    │ context.CancelFunc                      │ 取消信号         │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ CompletableFuture<T>          │ chan T (缓冲为 1)                       │ 一次性结果传递   │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ ConcurrentHashMap             │ sync.RWMutex 保护的 map 或 sync.Map     │ 并发安全 map     │
 ├───────────────────────────────┼─────────────────────────────────────────┼──────────────────┤
 │ AtomicBoolean / AtomicLong    │ sync/atomic 包                          │ 原子操作         │
 └───────────────────────────────┴─────────────────────────────────────────┴──────────────────┘
 5.2 SSE 事件状态机 (StreamEventAssembler)

 好消息： StreamEventAssembler 内部已经是命令式代码（非响应式），可以近乎 1:1 移植。

 type EventStreamState struct {
     seq                  atomic.Int64
     bootstrapEvents      []StreamEvent
     chatID, runID        string
     activeTaskID         string
     activeReasoningID    string
     activeContentID      string
     knownToolIDs         map[string]struct{} // 有序集合
     openToolIDs          map[string]struct{}
     toolArgChunkCounters map[string]*atomic.Int32
     terminated           bool
 }

 func (s *EventStreamState) Consume(envelope StreamEnvelope) []StreamEvent {
     switch input := envelope.Input().(type) {
     case *ReasoningDelta:
         return s.handleReasoningDelta(input, envelope.Actor())
     case *ContentDelta:
         return s.handleContentDelta(input, envelope.Actor())
     case *ToolArgs:
         return s.handleToolArgs(input, envelope.Actor())
     // ... 40+ 类型
     }
 }

 Go 的 type switch 直接替代 Java 的 instanceof 分派。StreamInput 的 40+ 变体定义在同一个包内，通过未导出方法 isStreamInput()
  限制实现。

 5.3 Agent 执行模式 (Sealed Class → Interface + Embedding)

 Java：
 public sealed abstract class AgentMode permits OneshotMode, ReactMode, PlanExecuteMode {
     protected final String systemPrompt;
     // ...
     public abstract void run(ExecutionContext ctx, Map<String, BaseTool> tools,
                              OrchestratorServices svc, FluxSink<AgentDelta> sink);
 }

 Go：
 type AgentMode interface {
     RuntimeMode() AgentRuntimeMode
     SystemPrompt() string
     SkillAppend() SkillAppend
     ToolAppend() ToolAppend
     DefaultRunSpec(config *AgentConfigFile) RunSpec
     Run(ctx context.Context, execCtx *ExecutionContext, tools map[string]BaseTool,
         svc *OrchestratorServices, sink chan<- AgentDelta)
 }

 type baseMode struct { // 共享字段通过嵌入实现
     systemPrompt string
     skillAppend  SkillAppend
     toolAppend   ToolAppend
 }

 type ReactMode struct {
     baseMode
     stage    StageSettings
     maxSteps int
 }

 5.4 LLM SSE 流式客户端

 Java 使用 WebClient + Reactor： 568 行复杂的响应式链。

 Go 大幅简化：
 func (c *OpenAIClient) StreamDeltas(ctx context.Context, spec LlmCallSpec) (<-chan LlmDelta, error) {
     req, _ := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(body))
     req.Header.Set("Authorization", "Bearer "+apiKey)
     req.Header.Set("Accept", "text/event-stream")

     ch := make(chan LlmDelta, 64)
     go func() {
         defer close(ch)
         resp, err := c.httpClient.Do(req)
         if err != nil { return }
         defer resp.Body.Close()

         scanner := bufio.NewScanner(resp.Body)
         for scanner.Scan() {
             line := scanner.Text()
             if !strings.HasPrefix(line, "data: ") { continue }
             data := line[6:]
             if data == "[DONE]" { return }
             if delta := c.parser.Parse(data); delta != nil {
                 select {
                 case ch <- *delta:
                 case <-ctx.Done(): return
                 }
             }
         }
     }()
     return ch, nil
 }

 取消机制天然通过 context.Context 传递到 http.Request，context 取消时 HTTP 连接自动断开。

 5.5 H2A RenderQueue 缓冲

 Java 使用 synchronized + AtomicBoolean + Reactor Scheduler。

 Go 方案：goroutine + time.Timer + select
 type RenderQueue struct {
     in  <-chan ServerSentEvent
     out chan ServerSentEvent
     cfg H2aConfig
 }

 func (q *RenderQueue) Run(ctx context.Context) {
     var buf []ServerSentEvent
     timer := time.NewTimer(q.cfg.FlushIntervalMs)
     defer timer.Stop()

     flush := func() {
         for _, e := range buf { q.out <- e }
         buf = buf[:0]
         timer.Reset(q.cfg.FlushIntervalMs)
     }

     for {
         select {
         case ev, ok := <-q.in:
             if !ok { flush(); close(q.out); return }
             if isTerminal(ev) { buf = append(buf, ev); flush(); continue }
             buf = append(buf, ev)
             if shouldFlush(buf, q.cfg) { flush() }
         case <-timer.C:
             if len(buf) > 0 { flush() }
         case <-ctx.Done():
             close(q.out); return
         }
     }
 }

 5.6 RunControl 取消机制

 Java： AtomicBoolean interrupted + Sinks.One<Void> cancelSink + AtomicReference<Thread> runnerThread

 Go： 天然适配
 type RunControl struct {
     ctx        context.Context
     cancel     context.CancelFunc    // 替代 Sinks.One<Void>
     interrupted atomic.Bool           // 快速检查标志
     broker     *RunInputBroker
 }

 func NewRunControl(parentCtx context.Context) *RunControl {
     ctx, cancel := context.WithCancel(parentCtx)
     return &RunControl{ctx: ctx, cancel: cancel, broker: NewRunInputBroker()}
 }

 func (rc *RunControl) Interrupt() {
     rc.interrupted.Store(true)
     rc.cancel()           // 触发 context 取消链
     rc.broker.Clear()     // 清空待处理队列
 }

 5.7 配置系统

 24 个 @ConfigurationProperties 合并为单一嵌套 struct：

 type Config struct {
     Server  ServerConfig  `koanf:"server"`
     Agent   AgentConfig   `koanf:"agent"`
     Memory  MemoryConfig  `koanf:"memory"`
     Logging LoggingConfig `koanf:"logging"`
 }

 type AgentConfig struct {
     Agents     AgentsConfig     `koanf:"agents"`
     Tools      ToolsConfig      `koanf:"tools"`
     Skills     SkillsConfig     `koanf:"skills"`
     Auth       AuthConfig       `koanf:"auth"`
     H2a        H2aConfig        `koanf:"h2a"`
     Cors       CorsConfig       `koanf:"cors"`
     McpServers McpConfig        `koanf:"mcp-servers"`
     Schedule   ScheduleConfig   `koanf:"schedule"`
     // ... 其余属性
 }

 koanf 加载器支持 application.yml + 环境变量 + configs/ 覆盖目录，完全匹配 Spring 行为。

 5.8 依赖注入：显式构造

 func main() {
     cfg := config.Load()

     // 基础设施
     db := openSQLite(cfg.Memory.Chats.Index.SQLiteFile)
     httpClient := &http.Client{Timeout: 30 * time.Second}

     // 注册表
     providerReg := provider.NewRegistry(cfg.Agent.Providers)
     modelReg := model.NewRegistry(cfg.Agent.Models)
     toolReg := tool.NewRegistry(cfg.Agent.Tools)
     // ...

     // 核心服务
     llmSvc := llm.NewService(providerReg, httpClient, cfg)
     querySvc := service.NewAgentQueryService(agentReg, streamer, renderQueue, ...)

     // 路由
     r := chi.NewRouter()
     r.Use(security.JWTMiddleware(cfg.Agent.Auth))
     r.Post("/api/query", handler.Query(querySvc))
     // ...
     http.ListenAndServe(":"+cfg.Server.Port, r)
 }

 ---
 六、分阶段迁移计划

 Phase 0: 基础骨架

 目标： Go 项目初始化、配置加载、HTTP 服务器骨架

 - go.mod 初始化，引入所有依赖
 - internal/config/ — koanf 加载器，读取 application.yml + 环境变量 + configs/ 覆盖
 - 将 24 个 Java properties 类映射为嵌套 Config struct
 - cmd/runner/main.go 骨架，chi 路由，健康检查端点
 - Makefile (build, test, run, docker)
 - Dockerfile (多阶段构建)

 前置依赖： 无

 Phase 1: SSE 管道核心 ⭐ (最高风险)

 目标： 实现从 LLM 输入到 SSE 输出的完整流式管道

 - internal/stream/model.go — StreamEvent, StreamInput (40+ 变体), StreamEnvelope
 - internal/stream/assembler.go — StreamEventAssembler 状态机 (745 行 Java → ~600 行 Go)
 - internal/stream/streamer.go — StreamSseStreamer (goroutine + channel)
 - internal/stream/render_queue.go — RenderQueue H2A 缓冲
 - internal/stream/flush_writer.go — SSE 写入器
 - 黄金文件测试套件： 从 Java 系统捕获 20+ 场景的 SSE 输出作为基准

 前置依赖： Phase 0

 Phase 2: LLM 客户端

 目标： OpenAI 兼容的 SSE 流式客户端

 - internal/llm/openai_client.go — bufio.Scanner 解析 SSE，channel 输出
 - internal/stream/adapter/openai_parser.go — 解析 data 行为 LlmDelta
 - internal/llm/service.go — LlmService 抽象
 - internal/provider/ — Provider 注册与配置
 - internal/model/model_def.go — ModelDefinition, ModelRegistryService
 - 重试逻辑：首个 chunk 前连接失败重试 1 次

 前置依赖： Phase 0, Phase 1 (LlmDelta 类型)

 Phase 3: Agent 执行引擎 ⭐ (最高工作量)

 目标： 三种执行模式及其编排

 - internal/model/delta.go — AgentDelta 所有变体
 - internal/agent/mode/mode.go — AgentMode interface + baseMode 嵌入
 - internal/agent/mode/oneshot.go — OneshotMode
 - internal/agent/mode/react.go — ReactMode (循环 + 工具执行)
 - internal/agent/mode/planexecute.go — PlanExecuteMode (plan/execute/summary 三阶段)
 - internal/agent/mode/orchestrator.go — OrchestratorServices (callModelTurnStreaming, injectPendingSteers)
 - internal/agent/mode/accumulator.go — ModelTurnAccumulator
 - internal/agent/runtime/context.go — ExecutionContext
 - internal/agent/runtime/run_control.go — RunControl (context + atomic + channel)
 - internal/agent/runtime/input_broker.go — RunInputBroker

 前置依赖： Phase 1, Phase 2

 Phase 4: 工具系统

 目标： 所有工具类型和执行管道

 - internal/tool/base.go — BaseTool interface, ToolKind
 - internal/tool/registry.go, file_registry.go — 工具注册与 YAML 加载
 - internal/tool/bash.go + bash_validator.go — SystemBash (601 行 + 校验器)
 - internal/tool/container_hub_bash.go + container_hub_client.go — 沙箱 bash
 - internal/tool/datetime.go, plan_tools.go — 内置工具
 - internal/agent/runtime/tool_execution.go — ToolExecutionService
 - internal/agent/runtime/frontend_submit.go — 前端工具提交等待 (channel 替代 CompletableFuture)

 前置依赖： Phase 3

 Phase 5: YAML 定义 & 热重载

 目标： 定义驱动的 Agent 加载和文件监控

 - internal/agent/definition_loader.go — AgentDefinitionLoader (722 行)
 - internal/agent/config_file.go — AgentConfigFile YAML schema
 - internal/agent/registry.go — AgentRegistry (sync.RWMutex 保护)
 - internal/service/watch_service.go — DirectoryWatchService (fsnotify + 防抖)
 - internal/agent/driven_agent.go — DefinitionDrivenAgent
 - internal/util/catalog_diff.go — 依赖感知级联刷新 (Provider → Model → Agent)

 前置依赖： Phase 3, Phase 4

 Phase 6: 聊天记忆 & SQLite

 目标： JSONL 持久化和 SQLite 索引

 - internal/memory/types.go — ChatMemoryTypes 所有嵌套类型
 - internal/memory/converter.go — StoredMessageConverter
 - internal/memory/store.go — ChatWindowMemoryStore (JSONL 读写 + 滑动窗口)
 - internal/chat/record_store.go — ChatRecordStore
 - internal/chat/index_repo.go — ChatIndexRepository (modernc.org/sqlite)
 - internal/chat/snapshot_builder.go — ChatEventSnapshotBuilder

 前置依赖： Phase 3

 Phase 7: 安全层

 目标： JWT/JWKS 认证和图片令牌

 - internal/security/jwt_middleware.go — chi 中间件，Bearer token 解析 + 验证
 - internal/security/jwks_verifier.go — JWKS URI 获取缓存 + 本地公钥文件回退
 - internal/security/chat_image_token.go — HMAC-SHA256 签名/验证 + 密钥轮换

 前置依赖： Phase 0

 Phase 8: HTTP 处理器

 目标： 所有 REST/SSE 端点

 - internal/handler/query.go — POST /api/query (SSE 流式输出 + [DONE] 终止帧)
 - internal/handler/submit.go / steer.go / interrupt.go
 - internal/handler/agents.go — GET /api/agents, /api/teams, /api/skills, /api/tools
 - internal/handler/chat.go — GET /api/chats, /api/chat
 - internal/handler/data.go — GET /api/data (静态文件)
 - internal/handler/viewport.go — GET /api/viewport
 - internal/handler/middleware.go — 请求日志、CORS、错误恢复

 前置依赖： Phase 1, Phase 3, Phase 7

 Phase 9: MCP & Viewport 服务器

 目标： MCP JSON-RPC 集成和 viewport 服务器支持

 - internal/mcp/ — Client, Registry, Sync, AvailabilityGate, ReconnectOrchestrator
 - internal/viewport/ — Registry, ServerRegistry, Sync

 前置依赖： Phase 5

 Phase 10: Container Hub 沙箱

 目标： 三级沙箱生命周期管理

 - internal/agent/runtime/sandbox.go — RUN/AGENT/GLOBAL 级别管理 + 引用计数 + 空闲驱逐
 - internal/agent/runtime/mount_resolver.go — 15+ 挂载点解析

 前置依赖： Phase 4

 Phase 11: 定时任务系统

 目标： YAML 驱动的 cron 调度

 - internal/schedule/ — Registry, Orchestrator (robfig/cron), Dispatch

 前置依赖： Phase 5, Phase 8

 Phase 12: 集成测试 & 收尾

 目标： 端到端验证、性能调优

 - 端到端集成测试 (真实 LLM provider)
 - 并发压力测试 (steer + interrupt 同时流式输出)
 - goroutine 泄漏检测 (uber-go/goleak)
 - pprof 性能分析
 - 配置迁移文档
 - Docker 部署指南

 前置依赖： 所有前置阶段

 ---
 七、测试策略

 7.1 黄金文件契约测试 (Phase 1 最高优先级)

 从 Java 系统捕获精确 SSE 输出作为基准文件，Go 系统必须产生相同输出。

 必须覆盖的场景：
 1. ONESHOT 简单查询 — content 完整流程
 2. REACT 带工具调用 — tool.start → tool.args → tool.end → tool.result
 3. PLAN_EXECUTE 完整循环 — plan create, task start/complete, reasoning
 4. Action 工具 — action.start → action.args → action.end → action.result
 5. Frontend 工具 + submit — tool.start → 等待 → request.submit → tool.result
 6. Steer 注入 — request.steer 中途注入
 7. Interrupt — run.cancel + 未关闭 block 自动关闭
 8. 流式错误 — run.error + 清理
 9. Heartbeat 交错
 10. RenderQueue 缓冲输出 (字符/事件/时间阈值)

 7.2 单元测试映射
 ┌───────────────────────────────────────┬───────────────────────────┬────────┐
 │              Java 测试类              │        Go 测试文件        │ 优先级 │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ StreamEventAssemblerTest              │ assembler_test.go         │ P0     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ OpenAiSseDeltaParserCompatibilityTest │ openai_parser_test.go     │ P0     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ ChatWindowMemoryStoreTest             │ store_test.go             │ P0     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ AgentDefinitionLoaderTest             │ definition_loader_test.go │ P1     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ ApiJwtAuthWebFilterTests              │ jwt_middleware_test.go    │ P1     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ ChatImageTokenServiceTest             │ chat_image_token_test.go  │ P1     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ ScheduledQueryRegistryServiceTest     │ registry_test.go          │ P1     │
 ├───────────────────────────────────────┼───────────────────────────┼────────┤
 │ Config binding tests (10+)            │ config_test.go            │ P2     │
 └───────────────────────────────────────┴───────────────────────────┴────────┘
 7.3 兼容性验证

 Java 和 Go 服务器使用相同配置目录并行运行，发送相同请求，diff SSE 事件流（忽略 timestamp/runId/seq），验证端到端兼容性。

 ---
 八、风险与缓解措施
 风险: SSE 事件顺序/状态机不一致
 严重性: 致命
 缓解措施: Phase 1 最先移植 StreamEventAssembler + 黄金文件测试；状态机本身是命令式代码，可 1:1 移植
 ────────────────────────────────────────
 风险: Channel 死锁 / Goroutine 泄漏
 严重性: 高
 缓解措施: 每个 goroutine 接受 context.Context + select on ctx.Done()；测试使用 goleak 检测；使用有缓冲 channel
 ────────────────────────────────────────
 风险: 迁移期间 Java 侧功能漂移
 严重性: 高
 缓解措施: 维护"迁移差异日志"追踪每个 Java 新提交；最后 2 周冻结 Java 功能开发；共享契约测试套件
 ────────────────────────────────────────
 风险: 配置绑定遗漏
 严重性: 中
 缓解措施: 编写测试加载完整 .env 并断言所有配置字段有值；保留 CLAUDE.md 环境变量映射表
 ────────────────────────────────────────
 风险: SQLite 交叉编译问题
 严重性: 中
 缓解措施: 使用 modernc.org/sqlite (纯 Go)，零 CGo 依赖
 ────────────────────────────────────────
 风险: 热重载并发安全
 严重性: 中
 缓解措施: AgentRegistry 使用 sync.RWMutex；读用 RLock，重载用 Lock；或使用 atomic.Pointer 指针交换
 ────────────────────────────────────────
 风险: 性能回归
 严重性: 低
 缓解措施: Go goroutine 比 Reactor 更轻量；LLM API 延迟 (100ms-10s) 远大于框架开销；使用 pprof 监控热路径
 ---
 九、关键优势预期

 1. 部署简化： 单一静态二进制，无 JVM，Docker 镜像从 ~500MB 降至 ~30MB
 2. 启动速度： 从 Spring Boot 数秒启动降至毫秒级
 3. 内存占用： 无 JVM heap 开销，预计降低 60-70%
 4. 并发模型： goroutine 是天然的 LLM 流式处理模型，比 Reactor 更直观
 5. 构建速度： Go 编译秒级完成 vs Maven 分钟级
 6. 交叉编译： GOOS=linux go build 直接输出目标平台二进制

 ---
 十、验证方法

 1. Phase 1 完成后： 黄金文件测试通过，SSE 输出与 Java 字节级一致
 2. Phase 3 完成后： 三种模式的执行流程测试通过，包含 steer/interrupt 场景
 3. Phase 8 完成后： 所有 REST 端点返回格式与 Java 一致，可用 curl/Postman 对比
 4. Phase 12： 并行运行 Java 和 Go 服务器，对同一组请求 diff 结果
 5. 上线前： 灰度切流，对比两个版本的 SSE 事件流、内存占用、延迟分布