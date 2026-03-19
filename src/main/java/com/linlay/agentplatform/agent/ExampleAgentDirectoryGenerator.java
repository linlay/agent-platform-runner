package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public class ExampleAgentDirectoryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExampleAgentDirectoryGenerator.class);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String GLOBAL_AGENTS_MD = """
            本文件用于存放当前 agent 的跨阶段共享工作指令。

            说明：
            - 长期稳定的人格、角色定位写在 SOUL.md。
            - 所有阶段共享的长期规则写在 AGENTS.md。
            - 具体阶段执行规则写在对应的 AGENTS.<stage>.md。
            - 若某条规则只适用于单个阶段，请不要重复写入 AGENTS.md。
            """;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public void generateAll(Path sourceDir, Path targetRoot, LocalDate scaffoldDate) {
        if (sourceDir == null || targetRoot == null || scaffoldDate == null) {
            throw new IllegalArgumentException("sourceDir, targetRoot and scaffoldDate are required");
        }
        Path normalizedSource = sourceDir.toAbsolutePath().normalize();
        Path normalizedTarget = targetRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedSource)) {
            throw new IllegalArgumentException("Source agents directory is not a directory: " + normalizedSource);
        }

        List<SourceAgent> agents = loadSourceAgents(normalizedSource);
        if (agents.isEmpty()) {
            throw new IllegalStateException("No example agents found in " + normalizedSource);
        }
        for (SourceAgent agent : agents) {
            Path agentDir = normalizedTarget.resolve(agent.key());
            if (Files.exists(agentDir)) {
                throw new IllegalStateException("Target agent directory already exists: " + agentDir);
            }
        }

        try {
            Files.createDirectories(normalizedTarget);
            for (SourceAgent agent : agents) {
                writeAgent(agent, normalizedTarget.resolve(agent.key()), scaffoldDate);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate directoryized example agents into " + normalizedTarget, ex);
        }
    }

    private List<SourceAgent> loadSourceAgents(Path sourceDir) {
        try (Stream<Path> stream = Files.list(sourceDir)) {
            List<Path> selected = YamlCatalogSupport.selectYamlFiles(
                    stream.filter(Files::isRegularFile).toList(),
                    "agent",
                    log
            );
            List<SourceAgent> agents = new ArrayList<>();
            for (Path file : selected) {
                String raw = Files.readString(file);
                JsonNode root = yamlMapper.readTree(raw);
                if (!(root instanceof ObjectNode objectNode)) {
                    throw new IllegalStateException("Example agent must be a YAML object: " + file);
                }
                String key = requiredText(root, "key");
                String name = requiredText(root, "name");
                String role = requiredText(root, "role");
                String description = requiredText(root, "description");
                AgentRuntimeMode mode = AgentRuntimeMode.valueOf(requiredText(root, "mode"));
                Map<String, String> stagePrompts = collectStagePrompts(root, mode);
                rewriteBridgePrompts(objectNode, mode);
                agents.add(new SourceAgent(file, objectNode, key, name, role, description, mode, stagePrompts));
            }
            return List.copyOf(agents);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read example agents from " + sourceDir, ex);
        }
    }

    private Map<String, String> collectStagePrompts(JsonNode root, AgentRuntimeMode mode) {
        Map<String, String> prompts = new LinkedHashMap<>();
        switch (mode) {
            case ONESHOT -> prompts.put("plain", requiredText(root.path("plain"), "systemPrompt", "plain.systemPrompt"));
            case REACT -> prompts.put("react", requiredText(root.path("react"), "systemPrompt", "react.systemPrompt"));
            case PLAN_EXECUTE -> {
                String planPrompt = requiredText(root.path("planExecute").path("plan"), "systemPrompt", "planExecute.plan.systemPrompt");
                String executePrompt = requiredText(root.path("planExecute").path("execute"), "systemPrompt", "planExecute.execute.systemPrompt");
                String summaryPrompt = optionalText(root.path("planExecute").path("summary"), "systemPrompt");
                prompts.put("plan", planPrompt);
                prompts.put("execute", executePrompt);
                prompts.put("summary", StringUtils.hasText(summaryPrompt) ? summaryPrompt.trim() : executePrompt);
            }
        }
        return Map.copyOf(prompts);
    }

    private void rewriteBridgePrompts(ObjectNode root, AgentRuntimeMode mode) {
        switch (mode) {
            case ONESHOT -> setNestedText(root, "plain", "systemPrompt", bridgePrompt("AGENTS.plain.md"));
            case REACT -> setNestedText(root, "react", "systemPrompt", bridgePrompt("AGENTS.react.md"));
            case PLAN_EXECUTE -> {
                JsonNode planExecuteNode = root.path("planExecute");
                if (!(planExecuteNode instanceof ObjectNode planExecute)) {
                    throw new IllegalStateException("Missing object node 'planExecute'");
                }
                setNestedText(planExecute, "plan", "systemPrompt", bridgePrompt("AGENTS.plan.md"));
                setNestedText(planExecute, "execute", "systemPrompt", bridgePrompt("AGENTS.execute.md"));
                ensureObjectNode(planExecute, "summary").put("systemPrompt", bridgePrompt("AGENTS.summary.md"));
            }
        }
    }

    private void writeAgent(SourceAgent agent, Path agentDir, LocalDate scaffoldDate) throws IOException {
        Files.createDirectories(agentDir);
        writeFile(agentDir.resolve("agent.yml"), toYaml(agent.root()));
        writeFile(agentDir.resolve("SOUL.md"), soulContent(agent));
        writeFile(agentDir.resolve("AGENTS.md"), GLOBAL_AGENTS_MD);
        writeStageFiles(agent, agentDir);
        writeScaffold(agentDir, scaffoldDate);
    }

    private void writeStageFiles(SourceAgent agent, Path agentDir) throws IOException {
        switch (agent.mode()) {
            case ONESHOT -> writeFile(agentDir.resolve("AGENTS.plain.md"), agent.stagePrompts().get("plain"));
            case REACT -> writeFile(agentDir.resolve("AGENTS.react.md"), agent.stagePrompts().get("react"));
            case PLAN_EXECUTE -> {
                writeFile(agentDir.resolve("AGENTS.plan.md"), agent.stagePrompts().get("plan"));
                writeFile(agentDir.resolve("AGENTS.execute.md"), agent.stagePrompts().get("execute"));
                writeFile(agentDir.resolve("AGENTS.summary.md"), agent.stagePrompts().get("summary"));
            }
        }
    }

    private void writeScaffold(Path agentDir, LocalDate scaffoldDate) throws IOException {
        String month = MONTH_FORMATTER.format(scaffoldDate);
        writeFile(agentDir.resolve("memory").resolve("memory.md"), "");
        writeFile(agentDir.resolve("memory").resolve(month).resolve(scaffoldDate + ".md"), "");
        writeFile(agentDir.resolve("experiences").resolve("web-scraping").resolve("site-a-login.md"), "");
        writeFile(agentDir.resolve("skills").resolve("custom_skill").resolve("SKILL.md"), """
                ---
                name: "Custom Skill"
                description: "scaffold placeholder"
                scaffold: true
                ---
                """);
        Files.createDirectories(agentDir.resolve("skills").resolve("custom_skill").resolve("scripts"));
        writeFile(agentDir.resolve("tools").resolve("custom_tool.yml"), """
                scaffold: true
                name: custom_tool
                label: Custom Tool
                description: scaffold placeholder
                type: function
                inputSchema:
                  type: object
                """);
    }

    private String soulContent(SourceAgent agent) {
        return """
                # Identity

                - key: %s
                - name: %s
                - role: %s
                - mode: %s

                ## Mission

                %s
                """.formatted(
                agent.key(),
                agent.name(),
                agent.role(),
                agent.mode().name(),
                agent.description()
        ).trim();
    }

    private String toYaml(ObjectNode root) {
        try {
            return yamlMapper.writer().writeValueAsString(root).trim() + "\n";
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize generated agent YAML", ex);
        }
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                content == null ? "" : content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private String bridgePrompt(String stageFile) {
        return "请遵守 SOUL.md、AGENTS.md 与 " + stageFile + " 中的全部指令完成任务。";
    }

    private String requiredText(JsonNode root, String fieldName) {
        String value = optionalText(root, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Missing required field '" + fieldName + "'");
        }
        return value.trim();
    }

    private String optionalText(JsonNode root, String fieldName) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode node = root.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.asText("");
        return value == null ? "" : value;
    }

    private void setNestedText(ObjectNode root, String sectionName, String fieldName, String value) {
        JsonNode child = root.path(sectionName);
        ensureObjectNode(root, sectionName).put(fieldName, value);
    }

    private ObjectNode ensureObjectNode(ObjectNode root, String sectionName) {
        JsonNode child = root.path(sectionName);
        if (child instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = yamlMapper.createObjectNode();
        root.set(sectionName, created);
        return created;
    }

    private String requiredText(JsonNode section, String fieldName, String path) {
        if (section == null || section.isMissingNode() || section.isNull()) {
            throw new IllegalStateException("Missing required object '" + path + "'");
        }
        JsonNode node = section.path(fieldName);
        String value = node.asText("");
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Missing required field '" + path + "'");
        }
        return value.trim();
    }

    public static String targetFileNameForStage(String stageKey) {
        return "AGENTS." + stageKey.toLowerCase(Locale.ROOT) + ".md";
    }

    private record SourceAgent(
            Path sourceFile,
            ObjectNode root,
            String key,
            String name,
            String role,
            String description,
            AgentRuntimeMode mode,
            Map<String, String> stagePrompts
    ) {
    }
}
