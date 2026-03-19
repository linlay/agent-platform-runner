package com.linlay.agentplatform.agent;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AgentMemoryService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public String loadMemory(Path agentDir) {
        return readOptional(agentMemoryPath(agentDir));
    }

    public String loadDailySummary(Path agentDir, LocalDate date) {
        if (agentDir == null || date == null) {
            return null;
        }
        return readOptional(resolveDailySummaryPath(agentDir, date));
    }

    public void writeMemory(Path agentDir, String content) {
        write(agentMemoryPath(agentDir), content);
    }

    public void writeDailySummary(Path agentDir, LocalDate date, String content) {
        if (agentDir == null || date == null) {
            return;
        }
        write(resolveDailySummaryPath(agentDir, date), content);
    }

    public List<String> loadRecentDailySummaries(Path agentDir, int days) {
        if (agentDir == null || days <= 0) {
            return List.of();
        }
        Path root = normalize(agentDir).resolve("memory");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.reverseOrder())
                    .limit(days)
                    .forEach(files::add);
        } catch (IOException ignored) {
            return List.of();
        }
        List<String> loaded = new ArrayList<>();
        for (Path file : files) {
            String text = readOptional(file);
            if (StringUtils.hasText(text)) {
                loaded.add(text);
            }
        }
        return List.copyOf(loaded);
    }

    private Path agentMemoryPath(Path agentDir) {
        if (agentDir == null) {
            return null;
        }
        return normalize(agentDir).resolve("memory").resolve("memory.md");
    }

    private Path resolveDailySummaryPath(Path agentDir, LocalDate date) {
        Path normalized = normalize(agentDir);
        return normalized.resolve("memory")
                .resolve(MONTH_FORMATTER.format(date))
                .resolve(DATE_FORMATTER.format(date) + ".md");
    }

    private String readOptional(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            String text = Files.readString(path);
            return StringUtils.hasText(text) ? text.trim() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void write(Path path, String content) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write agent memory file: " + path, ex);
        }
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
