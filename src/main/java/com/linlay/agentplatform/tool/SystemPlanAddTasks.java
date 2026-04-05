package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.engine.runtime.PlanToolConstants;
import com.linlay.agentplatform.util.IdGenerators;
import com.linlay.agentplatform.util.MapReaders;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SystemPlanAddTasks extends AbstractDeterministicTool {

    @Override
    public String name() {
        return PlanToolConstants.PLAN_ADD_TASKS_TOOL;
    }

    @Override
    public String description() {
        return "创建计划任务（追加模式），支持一次添加多个任务。每个任务必须包含 description（任务描述文本）。成功返回多行文本：taskId | status | description。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        List<TaskItem> tasks = new ArrayList<>();
        Object rawTasks = args == null ? null : args.get("tasks");
        if (rawTasks instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String description = readString(map, "description");
                if (description == null || description.isBlank()) {
                    continue;
                }
                String status = normalizePlanStatus(readString(map, "status"));
                tasks.add(new TaskItem(shortId(), description.trim(), status));
            }
        }

        String singleDescription = args == null ? null : readString(args, "description");
        if (tasks.isEmpty() && singleDescription != null && !singleDescription.isBlank()) {
            String status = normalizePlanStatus(args == null ? null : readString(args, "status"));
            tasks.add(new TaskItem(shortId(), singleDescription.trim(), status));
        }

        if (tasks.isEmpty()) {
            return OBJECT_MAPPER.getNodeFactory().textNode("失败: 缺少任务描述");
        }

        StringBuilder text = new StringBuilder();
        for (TaskItem task : tasks) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(task.taskId())
                    .append(" | ")
                    .append(task.status())
                    .append(" | ")
                    .append(task.description());
        }
        return OBJECT_MAPPER.getNodeFactory().textNode(text.toString());
    }

    private String readString(Map<?, ?> map, String key) {
        return MapReaders.readString(map, key);
    }

    private String shortId() {
        return IdGenerators.shortHexId();
    }

    private String normalizePlanStatus(String rawStatus) {
        String normalized = PlanTaskStatusNormalizer.normalizeStrict(rawStatus);
        return normalized == null ? "init" : normalized;
    }

    private record TaskItem(
            String taskId,
            String description,
            String status
    ) {
    }
}
