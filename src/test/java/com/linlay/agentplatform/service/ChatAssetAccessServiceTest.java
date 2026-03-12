package com.linlay.agentplatform.service;

import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAssetAccessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAllowCurrentChatDirectoryAssetsAndRejectOtherChats() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174020";
        String otherChatId = "123e4567-e89b-12d3-a456-426614174021";
        Files.createDirectories(tempDir.resolve(chatId));
        Files.write(tempDir.resolve(chatId).resolve("image.png"), new byte[]{1});
        Files.createDirectories(tempDir.resolve(otherChatId));
        Files.write(tempDir.resolve(otherChatId).resolve("image.png"), new byte[]{2});

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        when(chatRecordStore.loadChat(chatId, false))
                .thenReturn(new ChatDetailResponse(chatId, "chat", null, null, java.util.List.of(), null));

        ChatAssetAccessService service = new ChatAssetAccessService(chatRecordStore, newCatalogService());

        assertThat(service.canRead(chatId, chatId + "/image.png")).isTrue();
        assertThat(service.canRead(chatId, otherChatId + "/image.png")).isFalse();
    }

    private ChatAssetCatalogService newCatalogService() {
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir(tempDir.toString());
        return new ChatAssetCatalogService(new ChatDataPathService(dataProperties));
    }
}
