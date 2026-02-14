package com.linlay.springaiagw.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryWatchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldTriggerCallbackOnFileChange() throws Exception {
        Path watchedDir = tempDir.resolve("watched");
        Files.createDirectories(watchedDir);

        CountDownLatch latch = new CountDownLatch(1);
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(watchedDir, latch::countDown);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, dirs);
        try {
            Files.writeString(watchedDir.resolve("test.json"), "{}");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldHandleMissingDirectoryGracefully() {
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(tempDir.resolve("nonexistent"), () -> {});

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, dirs);
        service.destroy();
    }
}
