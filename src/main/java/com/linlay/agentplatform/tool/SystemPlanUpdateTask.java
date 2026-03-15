package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.agent.PlanToolConstants;
import com.linlay.agentplatform.util.MapReaders;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SystemPlanUpdateTask extends AbstractDeterministicTool {

    @Override
    public String name() {
        return PlanToolConstants.PLAN_UPDATE_TASK_TOOL;
    }

    @Override
    public String description() {
        return "更新计划中的任务状态。status 仅支持 init/completed/failed/canceled；成功返回 OK，失败返回 失败: 原因。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        String taskId = readString(args, "taskId");
        if (taskId == null || taskId.isBlank()) {
            return OBJECT_MAPPER.getNodeFactory().textNode("失败: 缺少 taskId");
        }
        String status = PlanTaskStatusNormalizer.normalizeStrict(readString(args, "status"));
        if (status == null) {
            return OBJECT_MAPPER.getNodeFactory().textNode("失败: 非法状态，仅支持 init/completed/failed/canceled");
        }
        return OBJECT_MAPPER.getNodeFactory().textNode("OK");
    }

    private String readString(Map<String, Object> args, String key) {
        return MapReaders.readString(args, key);
    }
}
