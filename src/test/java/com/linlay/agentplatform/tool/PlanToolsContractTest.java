package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PlanToolsContractTest {

    private static final Pattern TASK_LINE_PATTERN = Pattern.compile("^[a-f0-9]{8}\\s\\|\\s(init|completed|failed|canceled)\\s\\|\\s.+$");

    @Test
    void planAddTasksShouldReturnTaskLines() {
        SystemPlanAddTasks tool = new SystemPlanAddTasks();
        JsonNode result = tool.invoke(Map.of(
                "tasks",
                List.of(
                        Map.of("description", "任务一", "status", "init"),
                        Map.of("description", "任务二", "status", "completed")
                )
        ));

        assertThat(result.isTextual()).isTrue();
        String text = result.asText();
        assertThat(text).doesNotStartWith("失败:");
        String[] lines = text.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).matches(TASK_LINE_PATTERN);
        assertThat(lines[1]).matches(TASK_LINE_PATTERN);
    }

    @Test
    void planAddTasksShouldDefaultStatusToInitWhenMissing() {
        SystemPlanAddTasks tool = new SystemPlanAddTasks();
        JsonNode result = tool.invoke(Map.of(
                "tasks",
                List.of(Map.of("description", "任务一"))
        ));

        assertThat(result.isTextual()).isTrue();
        assertTaskLine(result.asText(), "init", "任务一");
    }

    @Test
    void planAddTasksShouldDefaultStatusToInitWhenInvalid() {
        SystemPlanAddTasks tool = new SystemPlanAddTasks();
        JsonNode result = tool.invoke(Map.of(
                "tasks",
                List.of(Map.of("description", "任务一", "status", "done"))
        ));

        assertThat(result.isTextual()).isTrue();
        assertTaskLine(result.asText(), "init", "任务一");
    }

    @Test
    void planAddTasksShouldKeepSingleTaskFallbackAndDefaultStatusToInit() {
        SystemPlanAddTasks tool = new SystemPlanAddTasks();

        JsonNode missingStatus = tool.invoke(Map.of("description", "扁平任务"));
        assertThat(missingStatus.isTextual()).isTrue();
        assertTaskLine(missingStatus.asText(), "init", "扁平任务");

        JsonNode invalidStatus = tool.invoke(Map.of("description", "扁平任务", "status", "pending"));
        assertThat(invalidStatus.isTextual()).isTrue();
        assertTaskLine(invalidStatus.asText(), "init", "扁平任务");
    }

    @Test
    void planAddTasksShouldFailWhenDescriptionIsMissing() {
        SystemPlanAddTasks tool = new SystemPlanAddTasks();

        JsonNode emptyTasks = tool.invoke(Map.of("tasks", List.of(Map.of("status", "init"))));
        assertThat(emptyTasks.isTextual()).isTrue();
        assertThat(emptyTasks.asText()).isEqualTo("失败: 缺少任务描述");

        JsonNode emptyPayload = tool.invoke(Map.of());
        assertThat(emptyPayload.isTextual()).isTrue();
        assertThat(emptyPayload.asText()).isEqualTo("失败: 缺少任务描述");
    }

    @Test
    void planUpdateTaskShouldReturnOkOrFailureText() {
        SystemPlanUpdateTask tool = new SystemPlanUpdateTask();

        JsonNode success = tool.invoke(Map.of("taskId", "abc12345", "status", "completed"));
        assertThat(success.isTextual()).isTrue();
        assertThat(success.asText()).isEqualTo("OK");

        JsonNode missingTask = tool.invoke(Map.of("status", "completed"));
        assertThat(missingTask.isTextual()).isTrue();
        assertThat(missingTask.asText()).startsWith("失败:");

        JsonNode invalidStatus = tool.invoke(Map.of("taskId", "abc12345", "status", "done"));
        assertThat(invalidStatus.isTextual()).isTrue();
        assertThat(invalidStatus.asText()).startsWith("失败:");
    }

    private void assertTaskLine(String text, String status, String description) {
        assertThat(text).matches(TASK_LINE_PATTERN);
        assertThat(text).contains(" | " + status + " | " + description);
    }
}
