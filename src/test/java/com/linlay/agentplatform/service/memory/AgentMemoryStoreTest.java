package com.linlay.agentplatform.service.memory;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteReadAndTrackAccess() throws Exception {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("remember this")).thenReturn(Optional.of(new float[]{1f, 0f}));

        AgentMemoryStore store = new AgentMemoryStore(properties(2), agentProperties(), embeddingService);
        Path agentDir = tempDir.resolve("agent-a");

        MemoryRecord written = store.write("agent-a", agentDir, "remember this", "fact", 8, List.of("alpha", "beta"));
        MemoryRecord read = store.read("agent-a", agentDir, written.id()).orElseThrow();

        assertThat(written.id()).startsWith("mem_");
        assertThat(read.content()).isEqualTo("remember this");
        assertThat(read.category()).isEqualTo("fact");
        assertThat(read.tags()).containsExactly("alpha", "beta");
        assertThat(read.hasEmbedding()).isTrue();
        assertThat(read.accessCount()).isEqualTo(1);
        assertThat(read.lastAccessedAt()).isNotNull();
        assertThat(Files.exists(agentDir.resolve("memory.db"))).isTrue();
    }

    @Test
    void shouldSearchByFtsWhenEmbeddingUnavailable() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("first keyword memory")).thenReturn(Optional.empty());
        when(embeddingService.embed("second note")).thenReturn(Optional.empty());
        when(embeddingService.embed("keyword")).thenReturn(Optional.empty());

        AgentMemoryStore store = new AgentMemoryStore(properties(2), agentProperties(), embeddingService);
        Path agentDir = tempDir.resolve("agent-b");

        store.write("agent-b", agentDir, "first keyword memory", "general", 5, List.of("hello"));
        store.write("agent-b", agentDir, "second note", "general", 5, List.of());

        List<ScoredMemory> results = store.search("agent-b", agentDir, "keyword", null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).memory().content()).isEqualTo("first keyword memory");
        assertThat(results.get(0).matchType()).isEqualTo("fts");
    }

    @Test
    void shouldCombineVectorAndFtsScores() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("alpha keyword memory")).thenReturn(Optional.of(new float[]{1f, 0f}));
        when(embeddingService.embed("beta note")).thenReturn(Optional.of(new float[]{0f, 1f}));
        when(embeddingService.embed("alpha keyword")).thenReturn(Optional.of(new float[]{1f, 0f}));

        AgentMemoryStore store = new AgentMemoryStore(properties(2), agentProperties(), embeddingService);
        Path agentDir = tempDir.resolve("agent-c");

        store.write("agent-c", agentDir, "alpha keyword memory", "general", 7, List.of());
        store.write("agent-c", agentDir, "beta note", "general", 7, List.of());

        List<ScoredMemory> results = store.search("agent-c", agentDir, "alpha keyword", null, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).memory().content()).isEqualTo("alpha keyword memory");
        assertThat(results.get(0).matchType()).isEqualTo("hybrid");
    }

    @Test
    void shouldResolveFlatAgentDatabasePathWhenAgentDirMissing() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("flat memory")).thenReturn(Optional.empty());

        AgentProperties agentProperties = agentProperties();
        agentProperties.setExternalDir(tempDir.resolve("agents").toString());
        AgentMemoryStore store = new AgentMemoryStore(properties(2), agentProperties, embeddingService);

        store.write("flat-agent", null, "flat memory", null, 5, List.of());

        assertThat(Files.exists(tempDir.resolve("agents/flat-agent/memory.db"))).isTrue();
    }

    @Test
    void shouldHandleConcurrentWrites() throws Exception {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());

        AgentMemoryStore store = new AgentMemoryStore(properties(2), agentProperties(), embeddingService);
        Path agentDir = tempDir.resolve("agent-d");
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        try {
            List<Callable<MemoryRecord>> tasks = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(index -> (Callable<MemoryRecord>) () -> store.write(
                            "agent-d",
                            agentDir,
                            "memory-" + index,
                            "general",
                            5,
                            List.of("tag-" + index)
                    ))
                    .toList();
            List<Future<MemoryRecord>> futures = executorService.invokeAll(tasks);
            for (Future<MemoryRecord> future : futures) {
                assertThat(future.get().id()).startsWith("mem_");
            }
        } finally {
            executorService.shutdownNow();
        }

        assertThat(store.list("agent-d", agentDir, null, 20, "recent")).hasSize(10);
    }

    private AgentMemoryProperties properties(int embeddingDimension) {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setEmbeddingDimension(embeddingDimension);
        properties.setSearchDefaultLimit(10);
        properties.setDbFileName("memory.db");
        return properties;
    }

    private AgentProperties agentProperties() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir(tempDir.resolve("agents").toString());
        return agentProperties;
    }
}
