package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.model.api.QueryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAssetCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldListMediaAssetsAndIgnoreHiddenFiles() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174010";
        Path chatDir = tempDir.resolve(chatId);
        Files.createDirectories(chatDir.resolve("sub"));
        Files.write(chatDir.resolve("a.png"), new byte[]{1});
        Files.write(chatDir.resolve("b.mp3"), new byte[]{2});
        Files.write(chatDir.resolve("sub").resolve("c.mp4"), new byte[]{3});
        Files.write(chatDir.resolve(".assets.json"), new byte[]{4});
        Files.write(chatDir.resolve(".hidden.png"), new byte[]{5});

        ChatAssetCatalogService service = newService();
        List<QueryRequest.Reference> references = service.listAssets(chatId);

        assertThat(references).hasSize(3);
        assertThat(references).extracting(QueryRequest.Reference::type)
                .containsExactlyInAnyOrder("image", "audio", "video");
        assertThat(references).extracting(QueryRequest.Reference::url)
                .containsExactlyInAnyOrder(
                        "/data/" + chatId + "/a.png",
                        "/data/" + chatId + "/b.mp3",
                        "/data/" + chatId + "/sub/c.mp4"
                );
        assertThat(references).allSatisfy(reference ->
                assertThat(reference.meta()).containsKey("relativePath"));
        assertThat(references).extracting(reference -> reference.meta().get("relativePath"))
                .containsExactlyInAnyOrder("a.png", "b.mp3", "sub/c.mp4");
    }

    private ChatAssetCatalogService newService() {
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir(tempDir.toString());
        return new ChatAssetCatalogService(new ChatDataPathService(dataProperties));
    }
}
