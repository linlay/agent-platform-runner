package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.model.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);
    private static final Pattern SYSTEM_PROMPT_MULTILINE_PATTERN =
            Pattern.compile("(\"systemPrompt\"\\s*:\\s*)\"\"\"([\\s\\S]*?)\"\"\"");

    private final ObjectMapper objectMapper;
    private final AgentCatalogProperties properties;

    public AgentDefinitionLoader(ObjectMapper objectMapper, AgentCatalogProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<AgentDefinition> loadAll() {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        builtInAgents().forEach(agent -> definitions.put(agent.id(), agent));
        loadExternalAgents().forEach(agent -> {
            AgentDefinition old = definitions.put(agent.id(), agent);
            if (old != null) {
                log.info("External agent '{}' overrides existing definition", agent.id());
            }
        });
        return List.copyOf(definitions.values());
    }

    private List<AgentDefinition> builtInAgents() {
        return List.of(
                new AgentDefinition(
                        "demoPlain",
                        "默认示例：直接正文回答",
                        ProviderType.SILICONFLOW,
                        "deepseek-ai/DeepSeek-V3",
                        "你是简洁的助理，优先给出可执行结论，控制在 120 字以内。",
                        false,
                        AgentMode.PLAIN_CONTENT
                ),
                new AgentDefinition(
                        "demoThink",
                        "默认示例：深度思考后回答",
                        ProviderType.BAILIAN,
                        "qwen3-max",
                        "你是高级顾问。请先做结构化思考，必要时给出可执行计划，再输出结论。",
                        true,
                        AgentMode.THINKING_AND_CONTENT
                ),
                new AgentDefinition(
                        "demoOps",
                        "默认示例：深度思考并可自主调用工具",
                        ProviderType.BAILIAN,
                        "qwen3-max",
                        "你是高级规划助手。请先思考并形成 plan，按需要调用工具，再总结输出。",
                        true,
                        AgentMode.THINKING_AND_CONTENT_WITH_DUAL_TOOL_CALLS
                )
        );
    }

    private List<AgentDefinition> loadExternalAgents() {
        Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            log.debug("External agents dir does not exist, skip loading: {}", dir);
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            log.warn("Configured external agents path is not a directory: {}", dir);
            return List.of();
        }

        List<AgentDefinition> loaded = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> tryLoadExternal(path).ifPresent(loaded::add));
        } catch (IOException ex) {
            log.warn("Cannot list external agents from {}", dir, ex);
        }

        if (!loaded.isEmpty()) {
            log.debug("Loaded {} external agents from {}", loaded.size(), dir);
        }
        return loaded;
    }

    private java.util.Optional<AgentDefinition> tryLoadExternal(Path file) {
        String fileName = file.getFileName().toString();
        String agentId = fileName.substring(0, fileName.length() - ".json".length()).trim();
        if (agentId.isEmpty()) {
            log.warn("Skip external agent with empty name: {}", file);
            return java.util.Optional.empty();
        }

        try {
            AgentConfigFile config = readAgentConfig(file);
            ProviderType providerType = config.getProviderType() == null ? ProviderType.BAILIAN : config.getProviderType();
            AgentMode mode = config.getMode() == null ? AgentMode.PLAIN_CONTENT : config.getMode();
            boolean deepThink = resolveDeepThink(config.getDeepThink(), mode);
            String model = normalize(config.getModel(), defaultModel(providerType));
            String systemPrompt = normalize(config.getSystemPrompt(), "你是通用助理，回答要清晰和可执行。");
            String description = normalize(config.getDescription(), "external agent from " + fileName);

            return java.util.Optional.of(new AgentDefinition(
                    agentId,
                    description,
                    providerType,
                    model,
                    systemPrompt,
                    deepThink,
                    mode
            ));
        } catch (IOException ex) {
            log.warn("Skip invalid external agent file: {}", file, ex);
            return java.util.Optional.empty();
        }
    }

    private AgentConfigFile readAgentConfig(Path file) throws IOException {
        String raw = Files.readString(file);
        String normalized = normalizeMultilineSystemPrompt(raw);
        return objectMapper.readValue(normalized, AgentConfigFile.class);
    }

    private String normalizeMultilineSystemPrompt(String rawJson) throws IOException {
        Matcher matcher = SYSTEM_PROMPT_MULTILINE_PATTERN.matcher(rawJson);
        if (!matcher.find()) {
            return rawJson;
        }

        StringBuffer rewritten = new StringBuffer();
        do {
            String content = stripOuterLineBreak(matcher.group(2));
            String escaped = objectMapper.writeValueAsString(content);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + escaped));
        } while (matcher.find());
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String stripOuterLineBreak(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultModel(ProviderType providerType) {
        return providerType == ProviderType.SILICONFLOW ? "deepseek-ai/DeepSeek-V3" : "qwen3-max";
    }

    private boolean resolveDeepThink(Boolean deepThink, AgentMode mode) {
        if (deepThink != null) {
            return deepThink;
        }
        return mode != AgentMode.PLAIN_CONTENT;
    }
}
