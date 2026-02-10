package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.springaiagw.model.AgentDelta;
import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.model.ProviderType;
import com.linlay.springaiagw.model.SseChunk;
import com.linlay.springaiagw.service.DeltaStreamService;
import com.linlay.springaiagw.service.LlmService;
import com.linlay.springaiagw.tool.BaseTool;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class DefinitionDrivenAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefinitionDrivenAgent.class);
    private static final int MAX_TOOL_CALLS = 6;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentDefinition definition;
    private final LlmService llmService;
    private final DeltaStreamService deltaStreamService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            DeltaStreamService deltaStreamService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.definition = definition;
        this.llmService = llmService;
        this.deltaStreamService = deltaStreamService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return definition.id();
    }

    @Override
    public ProviderType providerType() {
        return definition.providerType();
    }

    @Override
    public String model() {
        return definition.model();
    }

    @Override
    public String systemPrompt() {
        return definition.systemPrompt();
    }

    @Override
    public Flux<AgentDelta> stream(AgentRequest request) {
        log.info(
                "[agent:{}] stream start provider={}, model={}, deepThink={}, city={}, date={}, message={}",
                id(),
                providerType(),
                model(),
                definition.deepThink(),
                normalize(request.city(), "(empty)"),
                normalize(request.date(), "(empty)"),
                normalize(request.message(), "")
        );
        if (definition.deepThink()) {
            return deepThinkingFlow(request);
        }
        return plainContent(request);
    }

    private Flux<AgentDelta> plainContent(AgentRequest request) {
        Flux<String> contentTextFlux = llmService.streamContent(
                        providerType(),
                        model(),
                        systemPrompt(),
                        request.message(),
                        "agent-plain-content"
                )
                .switchIfEmpty(Flux.just("未获取到模型输出，请检查 provider/model/sysPrompt 配置。"))
                .onErrorResume(ex -> Flux.just("模型调用失败，请稍后重试。"));
        return contentTextFlux
                .map(AgentDelta::content)
                .concatWith(Flux.just(AgentDelta.finish("stop")));
    }

    private Flux<AgentDelta> deepThinkingFlow(AgentRequest request) {
        String city = normalize(request.city(), definition.defaultCity());
        String date = normalize(request.date(), "");
        String plannerPrompt = buildPlannerPrompt(request);
        log.info("[agent:{}] deep-think planner prompt:\n{}", id(), plannerPrompt);
        StringBuilder rawPlanBuffer = new StringBuilder();
        StringBuilder emittedThinking = new StringBuilder();

        Flux<AgentDelta> plannerThinkingFlux = llmService.streamContent(
                        providerType(),
                        model(),
                        plannerSystemPrompt(),
                        plannerPrompt,
                        "agent-deepthink-planner"
                )
                .handle((chunk, sink) -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    rawPlanBuffer.append(chunk);
                    String delta = extractNewThinkingDelta(rawPlanBuffer, emittedThinking);
                    if (!delta.isEmpty()) {
                        sink.next(AgentDelta.thinking(delta));
                    }
                });

        return Flux.concat(
                        Flux.just(AgentDelta.thinking("正在生成执行计划...\n")),
                        plannerThinkingFlux,
                        Flux.defer(() -> {
                            PlannerDecision decision = parsePlannerDecision(rawPlanBuffer.toString(), request);
                            log.info("[agent:{}] deep-think planner raw response:\n{}", id(), rawPlanBuffer);
                            log.info("[agent:{}] deep-think planner decision: {}", id(), toJson(decision));
                            Flux<AgentDelta> summaryThinkingFlux = emittedThinking.isEmpty()
                                    ? Flux.just(AgentDelta.thinking(buildThinkingText(decision)))
                                    : Flux.empty();

                            ToolExecution toolExecution = executePlannedTools(decision, request, city, date);
                            Flux<AgentDelta> toolFlux = Flux.fromIterable(toolExecution.deltas());
                            log.info("[agent:{}] deep-think tool execution records: {}", id(), toJson(toolExecution.records()));

                            String finalPrompt = buildFinalPrompt(request, decision, toolExecution.records());
                            log.info("[agent:{}] deep-think final prompt:\n{}", id(), finalPrompt);
                            Flux<AgentDelta> contentFlux = llmService.streamContent(
                                            providerType(),
                                            model(),
                                            systemPrompt(),
                                            finalPrompt,
                                            "agent-deepthink-final"
                                    )
                                    .switchIfEmpty(Flux.just("未获取到模型输出，请检查 provider/model/sysPrompt 配置。"))
                                    .onErrorResume(ex -> Flux.just("模型调用失败，请稍后重试。"))
                                    .map(AgentDelta::content);

                            return Flux.concat(summaryThinkingFlux, toolFlux, contentFlux, Flux.just(AgentDelta.finish("stop")));
                        })
                )
                .onErrorResume(ex -> Flux.concat(
                        Flux.defer(() -> {
                            log.error("[agent:{}] deep-think flow failed, fallback to plain content", id(), ex);
                            return Flux.empty();
                        }),
                        Flux.just(AgentDelta.thinking("深度思考流程失败，降级为直接回答。")),
                        llmService.streamContent(
                                        providerType(),
                                        model(),
                                        systemPrompt(),
                                        request.message(),
                                        "agent-deepthink-fallback"
                                )
                                .switchIfEmpty(Flux.just("未获取到模型输出，请稍后重试。"))
                                .onErrorResume(inner -> Flux.just("模型调用失败，请稍后重试。"))
                                .map(AgentDelta::content),
                        Flux.just(AgentDelta.finish("stop"))
                ));
    }

    private String extractNewThinkingDelta(StringBuilder rawPlanBuffer, StringBuilder emittedThinking) {
        String current = extractThinkingFieldValue(rawPlanBuffer.toString());
        if (current.isEmpty() || current.length() <= emittedThinking.length()) {
            return "";
        }
        String delta = current.substring(emittedThinking.length());
        emittedThinking.append(delta);
        return delta;
    }

    private String extractThinkingFieldValue(String rawPlan) {
        if (rawPlan == null || rawPlan.isBlank()) {
            return "";
        }

        int keyStart = rawPlan.indexOf("\"thinking\"");
        if (keyStart < 0) {
            return "";
        }

        int colon = rawPlan.indexOf(':', keyStart + 10);
        if (colon < 0) {
            return "";
        }

        int valueStart = skipWhitespace(rawPlan, colon + 1);
        if (valueStart >= rawPlan.length() || rawPlan.charAt(valueStart) != '"') {
            return "";
        }

        StringBuilder value = new StringBuilder();
        int i = valueStart + 1;
        while (i < rawPlan.length()) {
            char ch = rawPlan.charAt(i);
            if (ch == '"') {
                return value.toString();
            }
            if (ch != '\\') {
                value.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= rawPlan.length()) {
                return value.toString();
            }

            char escaped = rawPlan.charAt(i + 1);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 5 >= rawPlan.length()) {
                        return value.toString();
                    }
                    String hex = rawPlan.substring(i + 2, i + 6);
                    if (!isHex(hex)) {
                        value.append("\\u").append(hex);
                    } else {
                        value.append((char) Integer.parseInt(hex, 16));
                    }
                    i += 4;
                }
                default -> value.append(escaped);
            }
            i += 2;
        }

        return value.toString();
    }

    private int skipWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lower = ch >= 'a' && ch <= 'f';
            boolean upper = ch >= 'A' && ch <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    private PlannerDecision parsePlannerDecision(String rawPlan, AgentRequest request) {
        JsonNode root = readJsonObject(rawPlan);
        if (root == null || !root.isObject()) {
            return fallbackDecision(request, rawPlan);
        }

        String thinking = normalize(root.path("thinking").asText(), "正在分解问题并判断是否需要工具调用。");
        List<String> planSteps = readTextArray(root.path("plan"));

        List<PlannedToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = root.path("toolCalls");
        if (toolCallsNode.isArray()) {
            for (JsonNode callNode : toolCallsNode) {
                String toolName = normalize(callNode.path("name").asText(), "");
                if (toolName.isBlank()) {
                    continue;
                }

                Map<String, Object> arguments = new LinkedHashMap<>();
                JsonNode argumentsNode = callNode.path("arguments");
                if (argumentsNode.isObject()) {
                    Map<String, Object> converted = objectMapper.convertValue(argumentsNode, MAP_TYPE);
                    if (converted != null) {
                        arguments.putAll(converted);
                    }
                }
                toolCalls.add(new PlannedToolCall(toolName, arguments));
            }
        }

        return new PlannerDecision(thinking, planSteps, toolCalls);
    }

    private PlannerDecision fallbackDecision(AgentRequest request, String rawPlan) {
        String thinking = "根据用户问题生成计划，按需调用工具，最后输出可执行结论。";
        if (rawPlan != null && !rawPlan.isBlank()) {
            thinking += " 原始规划输出无法解析，已降级为最小执行计划。";
        }

        List<PlannedToolCall> toolCalls = List.of();
        if (shouldInspectLocalEnvironment(request.message())) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("command", suggestBashDefaultCommand(request.message()));
            toolCalls = List.of(new PlannedToolCall("bash", arguments));
            thinking += " 检测到本地巡检意图，已补充 bash 工具调用兜底。";
        }

        return new PlannerDecision(
                thinking,
                List.of("确认用户目标与输入约束", "判断是否需要工具辅助", "输出结论与下一步建议"),
                toolCalls
        );
    }

    private ToolExecution executePlannedTools(
            PlannerDecision decision,
            AgentRequest request,
            String city,
            String date
    ) {
        List<AgentDelta> deltas = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();

        int index = 1;
        for (PlannedToolCall plannedCall : decision.toolCalls()) {
            if (index > MAX_TOOL_CALLS) {
                break;
            }

            String toolName = plannedCall.name();
            Map<String, Object> args = enrichToolArguments(toolName, plannedCall.arguments(), request, city, date);
            String callId = "call_" + sanitize(toolName) + "_" + index++;

            deltas.add(AgentDelta.toolCalls(List.of(toolCall(callId, toolName, toJson(args)))));

            JsonNode result = safeInvoke(toolName, args);
            deltas.add(AgentDelta.toolResult(callId, result));

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("callId", callId);
            record.put("toolName", toolName);
            record.put("arguments", args);
            record.put("result", result);
            records.add(record);
        }

        return new ToolExecution(deltas, records);
    }

    private JsonNode safeInvoke(String toolName, Map<String, Object> args) {
        try {
            return toolRegistry.invoke(toolName, args);
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("tool", toolName);
            error.put("ok", false);
            error.put("error", ex.getMessage());
            return error;
        }
    }

    private Map<String, Object> enrichToolArguments(
            String toolName,
            Map<String, Object> original,
            AgentRequest request,
            String city,
            String date
    ) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (original != null) {
            args.putAll(original);
        }

        if ("mock_ops_runbook".equals(toolName)) {
            args.putIfAbsent("message", request.message());
            args.putIfAbsent("city", city);
        }
        if ("mock_city_datetime".equals(toolName)) {
            args.putIfAbsent("city", city);
        }
        if ("mock_city_weather".equals(toolName)) {
            args.putIfAbsent("city", city);
            args.putIfAbsent("date", date.isBlank() ? "today" : date);
        }
        if ("bash".equals(toolName)) {
            args.putIfAbsent("command", suggestBashDefaultCommand(request.message()));
        }

        return args;
    }

    private String buildThinkingText(PlannerDecision decision) {
        StringBuilder builder = new StringBuilder();
        builder.append(normalize(decision.thinking(), "正在拆解问题并生成执行路径。"));

        if (!decision.plan().isEmpty()) {
            builder.append("\n计划：");
            int i = 1;
            for (String step : decision.plan()) {
                builder.append("\n").append(i++).append(". ").append(step);
            }
        }

        if (!decision.toolCalls().isEmpty()) {
            builder.append("\n计划工具调用：");
            List<String> names = decision.toolCalls().stream().map(PlannedToolCall::name).toList();
            builder.append(String.join(", ", names));
        }

        return builder.toString();
    }

    private String buildPlannerPrompt(AgentRequest request) {
        String tools = toolRegistry.list().stream()
                .sorted(Comparator.comparing(BaseTool::name))
                .map(tool -> "- " + tool.name() + "：" + tool.description() + "；参数建议：" + argumentHints(tool.name()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 无可用工具");

        return """
                用户问题：%s
                可用工具：
                %s

                请只输出 JSON（不要代码块、不要额外解释），格式：
                {
                  "thinking": "你的关键思考",
                  "plan": ["步骤1", "步骤2"],
                  "toolCalls": [{"name": "tool_name", "arguments": {"k": "v"}}]
                }

                约束：
                1) thinking 用中文，一句话。
                2) plan 输出 1-4 条可执行步骤。
                3) toolCalls 只在必要时填写，最多 %d 个；name 必须来自工具列表。
                4) 需要查看本地文件、目录、磁盘或系统状态时，优先使用 bash。
                5) city/date 等上下文在工具执行阶段会自动补齐，不必重复猜测。
                """.formatted(
                request.message(),
                tools,
                MAX_TOOL_CALLS
        );
    }

    private String plannerSystemPrompt() {
        return normalize(systemPrompt(), "你是通用助理")
                + "\n你当前处于任务编排阶段：先深度思考，再给出计划，并按需声明工具调用。";
    }

    private String buildFinalPrompt(
            AgentRequest request,
            PlannerDecision decision,
            List<Map<String, Object>> toolRecords
    ) {
        String toolResultJson = toJson(toolRecords);
        String planText = decision.plan().isEmpty() ? "[]" : String.join(" | ", decision.plan());

        return """
                用户问题：%s
                思考摘要：%s
                计划步骤：%s
                工具执行结果(JSON)：%s

                请基于以上信息输出最终回答：
                1) 先给结论。
                2) 若有工具结果，引用关键结果再总结。
                3) 必要时给简短行动建议。
                4) 保持简洁、可执行。
                """.formatted(
                request.message(),
                normalize(decision.thinking(), "(empty)"),
                planText,
                toolResultJson
        );
    }

    private List<String> readTextArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = normalize(item.asText(), "");
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private JsonNode readJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).trim();
            if (normalized.startsWith("json")) {
                normalized = normalized.substring(4).trim();
            }
        }

        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ex) {
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            String body = normalized.substring(start, end + 1);
            try {
                return objectMapper.readTree(body);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String argumentHints(String toolName) {
        return switch (toolName) {
            case "mock_city_datetime" -> "city";
            case "mock_city_weather" -> "city,date";
            case "mock_ops_runbook" -> "message,city";
            case "bash" -> "command";
            default -> "按工具说明填写";
        };
    }

    private SseChunk.ToolCall toolCall(String callId, String toolName, String arguments) {
        return new SseChunk.ToolCall(callId, "function", new SseChunk.Function(toolName, arguments));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize json", ex);
        }
    }

    private String sanitize(String input) {
        return normalize(input, "tool").replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean shouldInspectLocalEnvironment(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("文件")
                || normalized.contains("目录")
                || normalized.contains("本地")
                || normalized.contains("磁盘")
                || normalized.contains("系统状态")
                || normalized.contains("workspace")
                || normalized.contains("file")
                || normalized.contains("folder")
                || normalized.contains("disk")
                || normalized.contains("cpu")
                || normalized.contains("memory");
    }

    private String suggestBashDefaultCommand(String message) {
        String fallback = normalize(definition.defaultBashCommand(), "df -h");
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("文件") || normalized.contains("file") || normalized.contains("代码")) {
            return "ls";
        }
        if (normalized.contains("目录") || normalized.contains("folder") || normalized.contains("路径") || normalized.contains("pwd")) {
            return "pwd";
        }
        if (normalized.contains("磁盘") || normalized.contains("disk")) {
            return "df -h";
        }
        if (normalized.contains("内存") || normalized.contains("memory")) {
            return "free";
        }
        if (normalized.contains("cpu") || normalized.contains("负载") || normalized.contains("load")) {
            return "top";
        }
        return fallback;
    }

    private record PlannerDecision(
            String thinking,
            List<String> plan,
            List<PlannedToolCall> toolCalls
    ) {
    }

    private record PlannedToolCall(
            String name,
            Map<String, Object> arguments
    ) {
    }

    private record ToolExecution(
            List<AgentDelta> deltas,
            List<Map<String, Object>> records
    ) {
    }
}
