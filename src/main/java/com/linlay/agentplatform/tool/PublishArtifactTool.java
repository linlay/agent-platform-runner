package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.service.chat.ArtifactPublishService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
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
        return "批量发布当前运行中生成的文件产出物，并为每个产物触发独立 artifact.publish 事件。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        throw new IllegalArgumentException("_artifact_publish_ requires execution context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        List<ArtifactPublishService.ArtifactRequest> requests = readRequests(root);

        List<ArtifactPublishService.Publication> publications = artifactPublishService.publish(requests, context);
        for (ArtifactPublishService.Publication publication : publications) {
            context.deferToolDelta(AgentDelta.artifactPublished(
                    publication.artifactId(),
                    publication.chatId(),
                    publication.runId(),
                    publication.eventArtifact()
            ));
        }
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("ok", true);
        ArrayNode artifacts = result.putArray("artifacts");
        for (ArtifactPublishService.Publication publication : publications) {
            ObjectNode item = artifacts.addObject();
            item.put("artifactId", publication.artifactId());
            item.set("artifact", OBJECT_MAPPER.valueToTree(publication.artifact()));
        }
        return result;
    }

    private List<ArtifactPublishService.ArtifactRequest> readRequests(JsonNode root) {
        JsonNode artifacts = root == null ? null : root.get("artifacts");
        if (artifacts == null || artifacts.isNull() || !artifacts.isArray() || artifacts.isEmpty()) {
            throw new IllegalArgumentException("artifacts must be a non-empty array");
        }
        List<ArtifactPublishService.ArtifactRequest> requests = new ArrayList<>();
        for (int index = 0; index < artifacts.size(); index++) {
            JsonNode item = artifacts.get(index);
            if (item == null || item.isNull() || !item.isObject()) {
                throw new IllegalArgumentException("artifacts[" + index + "] must be an object");
            }
            String path = requireText(item, "artifacts[" + index + "].path");
            requests.add(new ArtifactPublishService.ArtifactRequest(
                    path,
                    readText(item, "name"),
                    readText(item, "description")
            ));
        }
        return List.copyOf(requests);
    }

    private String requireText(JsonNode root, String fieldName) {
        String fieldKey = fieldName;
        int index = fieldName.lastIndexOf('.');
        if (index >= 0 && index + 1 < fieldName.length()) {
            fieldKey = fieldName.substring(index + 1);
        }
        String value = readText(root, fieldKey);
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
