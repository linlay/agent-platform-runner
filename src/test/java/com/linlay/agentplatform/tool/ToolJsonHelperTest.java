package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolJsonHelperTest {

    @Test
    void shouldTrimAndRequireText() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of("content", "  hello  "));

        assertThat(ToolJsonHelper.readText(root, "content")).isEqualTo("hello");
        assertThat(ToolJsonHelper.requireText(root, "content")).isEqualTo("hello");
    }

    @Test
    void shouldUseDisplayNameForMissingRequiredText() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of("path", "   "));

        assertThatThrownBy(() -> ToolJsonHelper.requireText(root, "path", "artifacts[0].path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing argument: artifacts[0].path");
    }

    @Test
    void shouldParseIntegerValues() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of(
                "limit", " 7 ",
                "importance", 9
        ));

        assertThat(ToolJsonHelper.readInteger(root, "limit")).isEqualTo(7);
        assertThat(ToolJsonHelper.readInteger(root, "importance")).isEqualTo(9);
    }

    @Test
    void shouldRejectInvalidIntegerValues() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of("limit", "abc"));

        assertThatThrownBy(() -> ToolJsonHelper.readInteger(root, "limit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid argument: limit must be an integer");
    }

    @Test
    void shouldReadTrimmedStringList() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of(
                "tags", List.of(" alpha ", "", "beta")
        ));

        assertThat(ToolJsonHelper.readStringList(root.get("tags"))).containsExactly("alpha", "beta");
    }

    @Test
    void shouldRejectNonArrayStringList() {
        JsonNode root = AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(Map.of("tags", "alpha"));

        assertThatThrownBy(() -> ToolJsonHelper.readStringList(root.get("tags")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid argument: tags must be an array of strings");
    }
}
