package com.linlay.agentplatform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(watchedDir.resolve("test.yml"), "name: test\n");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldNotKeepTriggeringForSingleWrite() throws Exception {
        Path watchedDir = tempDir.resolve("watched-single");
        Files.createDirectories(watchedDir);

        AtomicInteger counter = new AtomicInteger();
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(watchedDir, counter::incrementAndGet);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(watchedDir.resolve("once.yml"), "name: once\n");

            boolean firstTriggered = waitForCountAtLeast(counter, 1, 10_000);
            assertThat(firstTriggered).isTrue();

            int snapshot = counter.get();
            TimeUnit.MILLISECONDS.sleep(1_200);
            assertThat(counter.get()).isEqualTo(snapshot);
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldTriggerAgainAfterDebounceWindow() throws Exception {
        Path watchedDir = tempDir.resolve("watched-debounce");
        Files.createDirectories(watchedDir);

        AtomicInteger counter = new AtomicInteger();
        Path file = watchedDir.resolve("twice.yml");
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(watchedDir, counter::incrementAndGet);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(file, "name: twice\nvalue: 1\n");
            boolean firstTriggered = waitForCountAtLeast(counter, 1, 10_000);
            assertThat(firstTriggered).isTrue();

            TimeUnit.MILLISECONDS.sleep(650);
            Files.writeString(file, "name: twice\nvalue: 2\n");

            boolean secondTriggered = waitForCountAtLeast(counter, 2, 10_000);
            assertThat(secondTriggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldHandleMissingDirectoryGracefully() {
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(tempDir.resolve("nonexistent"), () -> {});

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        service.destroy();
    }

    @Test
    void shouldTriggerSkillRefreshCallbackOnFileChange() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        CountDownLatch latch = new CountDownLatch(1);
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(skillsDir, latch::countDown);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(skillsDir.resolve("demo.txt"), "ok");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldHandleMissingSkillsDirectoryGracefully() {
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(tempDir.resolve("missing-skills"), () -> {});

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        service.destroy();
    }

    @Test
    void shouldTriggerModelRefreshCallbackOnFileChange() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);

        CountDownLatch latch = new CountDownLatch(1);
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(modelsDir, latch::countDown);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(modelsDir.resolve("demo-model.yml"), "key: demo-model\nprovider: demo\nprotocol: OPENAI\nmodelId: demo\n");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldTriggerTeamRefreshCallbackOnFileChange() throws Exception {
        Path teamsDir = tempDir.resolve("teams");
        Files.createDirectories(teamsDir);

        CountDownLatch latch = new CountDownLatch(1);
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(teamsDir, latch::countDown);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(teamsDir.resolve("a1b2c3d4e5f6.yml"), "name: Team\ndefaultAgentKey: demo\n");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldTriggerScheduleRefreshCallbackOnFileChange() throws Exception {
        Path schedulesDir = tempDir.resolve("schedules");
        Files.createDirectories(schedulesDir);

        CountDownLatch latch = new CountDownLatch(1);
        Map<Path, Runnable> dirs = new LinkedHashMap<>();
        dirs.put(schedulesDir, latch::countDown);

        DirectoryWatchService service = new DirectoryWatchService(null, null, null, null, dirs);
        try {
            Files.writeString(schedulesDir.resolve("demo_daily_summary.yml"), "name: demo\ndescription: test\n");
            boolean triggered = latch.await(10, TimeUnit.SECONDS);
            assertThat(triggered).isTrue();
        } finally {
            service.destroy();
        }
    }

    private boolean waitForCountAtLeast(AtomicInteger counter, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (counter.get() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return counter.get() >= expected;
    }
}
