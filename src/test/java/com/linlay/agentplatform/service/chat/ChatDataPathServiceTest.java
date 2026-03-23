package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.config.DataProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatDataPathServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRecognizeChatScopedAssetPaths() {
        ChatDataPathService service = newService();
        String chatId = "123e4567-e89b-12d3-a456-426614174001";

        assertThat(service.isChatAssetPath(chatId + "/cover.png")).isTrue();
        assertThat(service.belongsToChat(chatId, chatId + "/cover.png")).isTrue();
        assertThat(service.belongsToChat(chatId, "123e4567-e89b-12d3-a456-426614174002/cover.png")).isFalse();
    }

    @Test
    void shouldRejectInvalidChatIdAndTraversal() {
        ChatDataPathService service = newService();

        assertThatThrownBy(() -> service.resolveChatDir("bad-chat-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chatId");
        assertThatThrownBy(() -> service.normalizeRelativePath("../escape.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relativePath");
    }

    private ChatDataPathService newService() {
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir(tempDir.toString());
        return new ChatDataPathService(dataProperties);
    }
}
