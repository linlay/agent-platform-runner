package com.linlay.agentplatform.chat.asset;

import com.linlay.agentplatform.chat.upload.ChatUploadManifestStore;
import com.linlay.agentplatform.config.properties.ChatStorageProperties;
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
                        "/api/resource?file=" + chatId + "%2Fa.png",
                        "/api/resource?file=" + chatId + "%2Fb.mp3",
                        "/api/resource?file=" + chatId + "%2Fsub%2Fc.mp4"
                );
        assertThat(references).allSatisfy(reference ->
                assertThat(reference.meta()).containsKey("relativePath"));
        assertThat(references).extracting(reference -> reference.meta().get("relativePath"))
                .containsExactlyInAnyOrder("a.png", "b.mp3", "sub/c.mp4");
    }

    @Test
    void shouldPreferCompletedUploadManifestReferenceIds() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174011";
        Path chatDir = tempDir.resolve(chatId);
        Files.createDirectories(chatDir.resolve("uploads"));
        Files.writeString(chatDir.resolve("uploads").resolve("plan.txt"), "hello");
        ChatUploadManifestStore.write(chatDir, new ChatUploadManifestStore.StoredUpload(
                "req-1",
                chatId,
                "f1",
                "file",
                "plan.txt",
                5L,
                "text/plain",
                null,
                "uploads/plan.txt",
                ChatUploadManifestStore.STATUS_COMPLETED,
                1L,
                2L
        ));

        ChatAssetCatalogService service = newService();
        List<QueryRequest.Reference> references = service.listAssets(chatId);

        assertThat(references).hasSize(1);
        assertThat(references.getFirst().id()).isEqualTo("f1");
        assertThat(references.getFirst().url()).isEqualTo("/api/resource?file=" + chatId + "%2Fuploads%2Fplan.txt");
        assertThat(references.getFirst().meta()).containsEntry("origin", "upload");
    }

    private ChatAssetCatalogService newService() {
        ChatStorageProperties chatStorageProperties = new ChatStorageProperties();
        chatStorageProperties.setDir(tempDir.toString());
        return new ChatAssetCatalogService(new ChatDataPathService(chatStorageProperties));
    }
}
