package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.service.chat.ArtifactPublishService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class PublishArtifactTool extends AbstractDeterministicTool implements ContextAwareTool {

    private final ArtifactPublishService artifactPublishService;

    public PublishArtifactTool(ArtifactPublishService artifactPublishService) {
        this.artifactPublishService = artifactPublishService;
    }

    @Override
    public String name() {
        return "_artifact_publish_";
    }

    @Override
    public String description() {
        return "发布当前运行中生成的文件产出物，并触发独立 artifact.publish 事件。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        throw new IllegalArgumentException("_artifact_publish_ requires execution context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String path = requireText(root, "path");
        String name = readText(root, "name");
        String description = readText(root, "description");

        ArtifactPublishService.Publication publication = artifactPublishService.publish(path, name, description, context);
        context.deferToolDelta(AgentDelta.artifactPublished(
                publication.artifactId(),
                publication.chatId(),
                publication.runId(),
                publication.eventArtifact()
        ));
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("ok", true);
        result.put("artifactId", publication.artifactId());
        result.set("artifact", OBJECT_MAPPER.valueToTree(publication.artifact()));
        return result;
    }

    private String requireText(JsonNode root, String fieldName) {
        String value = readText(root, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing argument: " + fieldName);
        }
        return value;
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
