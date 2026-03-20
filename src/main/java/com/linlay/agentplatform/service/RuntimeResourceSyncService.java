package com.linlay.agentplatform.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;

import jakarta.annotation.PostConstruct;

@Component
public class RuntimeResourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeResourceSyncService.class);

    private final ResourcePatternResolver resourceResolver;
    private final Path toolsDir;
    private final Path skillsDir;
    private final Path schedulesDir;
    private final Path viewportsDir;

    @Autowired
    public RuntimeResourceSyncService(
            ToolProperties toolProperties,
            SkillProperties skillProperties,
            ScheduleProperties scheduleProperties,
            ViewportProperties viewportProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                normalizeDir(toolProperties == null ? null : toolProperties.getExternalDir()),
                normalizeDir(skillProperties == null ? null : skillProperties.getExternalDir()),
                normalizeDir(scheduleProperties == null ? null : scheduleProperties.getExternalDir()),
                normalizeDir(viewportProperties == null ? null : viewportProperties.getExternalDir())
        );
    }

    RuntimeResourceSyncService(
            ScheduleProperties scheduleProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                null,
                null,
                normalizeDir(scheduleProperties == null ? null : scheduleProperties.getExternalDir()),
                null
        );
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path schedulesDir
    ) {
        this(resourceResolver, null, null, schedulesDir, null);
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path toolsDir,
            Path skillsDir,
            Path schedulesDir
    ) {
        this(resourceResolver, toolsDir, skillsDir, schedulesDir, null);
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path toolsDir,
            Path skillsDir,
            Path schedulesDir,
            Path viewportsDir
    ) {
        this.resourceResolver = resourceResolver;
        this.toolsDir = normalizeDir(toolsDir);
        this.skillsDir = normalizeDir(skillsDir);
        this.schedulesDir = normalizeDir(schedulesDir);
        this.viewportsDir = normalizeDir(viewportsDir);
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path toolsDir,
            Path skillsDir
    ) {
        this(resourceResolver, toolsDir, skillsDir, null, null);
    }

    RuntimeResourceSyncService(
            ToolProperties toolProperties,
            SkillProperties skillProperties,
            ScheduleProperties scheduleProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                normalizeDir(toolProperties == null ? null : toolProperties.getExternalDir()),
                normalizeDir(skillProperties == null ? null : skillProperties.getExternalDir()),
                normalizeDir(scheduleProperties == null ? null : scheduleProperties.getExternalDir()),
                null
        );
    }

    @PostConstruct
    public void syncRuntimeDirectories() {
        syncClasspathResourceDirectory("tools", toolsDir);
        syncClasspathResourceDirectory("skills", skillsDir);
        syncResourceDirectory("schedules", schedulesDir);
        syncProjectDirectory("example/tools", toolsDir, false, path -> isYamlFile(path.getFileName().toString()));
        syncProjectDirectory("example/viewports", viewportsDir, true, path -> isViewportFile(path.getFileName().toString()));
    }

    private void syncClasspathResourceDirectory(String resourceDir, Path targetDir) {
        syncResourceDirectory(resourceDir, targetDir, true);
    }

    private void syncResourceDirectory(String resourceDir, Path targetDir) {
        syncResourceDirectory(resourceDir, targetDir, true);
    }

    private void syncResourceDirectory(String resourceDir, Path targetDir, boolean overwriteExisting) {
        if (targetDir == null) {
            return;
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException ex) {
            log.warn("Cannot create runtime directory {}", targetDir, ex);
            return;
        }

        String pattern = "classpath*:/" + resourceDir + "/**";
        Resource[] resources;
        try {
            resources = resourceResolver.getResources(pattern);
        } catch (IOException ex) {
            log.warn("Cannot scan resources with pattern {}", pattern, ex);
            return;
        }

        for (Resource resource : resources) {
            if (resource == null || !resource.exists() || !resource.isReadable()) {
                continue;
            }
            String relativePath = resolveRelativePath(resourceDir, resource);
            if (relativePath == null || relativePath.isBlank() || relativePath.endsWith("/")) {
                continue;
            }

            Path target = targetDir.resolve(relativePath).normalize();
            if (!target.startsWith(targetDir)) {
                log.warn("Skip suspicious resource path {} -> {}", relativePath, target);
                continue;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                if (!overwriteExisting && Files.exists(target)) {
                    continue;
                }
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Synced runtime resource {} -> {}", resource, target);
            } catch (IOException ex) {
                log.warn("Failed to sync runtime resource {} -> {}", resource, target, ex);
            }
        }
    }

    private void syncProjectDirectory(
            String projectRelativeDir,
            Path targetDir,
            boolean overwriteExisting,
            Predicate<Path> filter
    ) {
        if (targetDir == null || projectRelativeDir == null || projectRelativeDir.isBlank()) {
            return;
        }
        Path sourceDir = resolveProjectPath(projectRelativeDir);
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return;
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException ex) {
            log.warn("Cannot create runtime directory {}", targetDir, ex);
            return;
        }

        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .filter(path -> filter == null || filter.test(path))
                    .forEach(path -> copyProjectFile(sourceDir, path, targetDir, overwriteExisting));
        } catch (IOException ex) {
            log.warn("Cannot scan project directory {}", sourceDir, ex);
        }
    }

    private void copyProjectFile(Path sourceRoot, Path sourceFile, Path targetDir, boolean overwriteExisting) {
        if (sourceRoot == null || sourceFile == null || targetDir == null) {
            return;
        }
        Path relativePath = sourceRoot.relativize(sourceFile);
        Path target = targetDir.resolve(relativePath).normalize();
        if (!target.startsWith(targetDir)) {
            log.warn("Skip suspicious project resource path {} -> {}", relativePath, target);
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            if (!overwriteExisting && Files.exists(target)) {
                return;
            }
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Synced project resource {} -> {}", sourceFile, target);
        } catch (IOException ex) {
            log.warn("Failed to sync project resource {} -> {}", sourceFile, target, ex);
        }
    }

    private String resolveRelativePath(String resourceDir, Resource resource) {
        try {
            String raw = resource.getURL().toString().replace('\\', '/');
            String marker = "/" + resourceDir.toLowerCase(Locale.ROOT) + "/";
            String lower = raw.toLowerCase(Locale.ROOT);
            int idx = lower.lastIndexOf(marker);
            if (idx < 0) {
                return null;
            }
            return raw.substring(idx + marker.length());
        } catch (IOException ex) {
            return null;
        }
    }

    private Path resolveProjectPath(String projectRelativeDir) {
        Path projectRoot = resolveProjectRoot();
        if (projectRoot == null) {
            return null;
        }
        return projectRoot.resolve(projectRelativeDir).normalize();
    }

    private Path resolveProjectRoot() {
        try {
            Path location = Path.of(RuntimeResourceSyncService.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (Files.isDirectory(location) && "classes".equals(location.getFileName().toString())) {
                Path targetDir = location.getParent();
                if (targetDir != null && "target".equals(targetDir.getFileName().toString())) {
                    return targetDir.getParent();
                }
            }
            if (Files.isRegularFile(location)) {
                Path parent = location.getParent();
                if (parent != null) {
                    return parent;
                }
            }
        } catch (URISyntaxException | NullPointerException ex) {
            log.debug("Cannot resolve project root from code source", ex);
        }
        return null;
    }

    private static Path normalizeDir(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private static Path normalizeDir(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private boolean isYamlFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private boolean isViewportFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".qlc");
    }

}
