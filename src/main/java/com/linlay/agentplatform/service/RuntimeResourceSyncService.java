package com.linlay.agentplatform.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;

import jakarta.annotation.PostConstruct;

@Component
public class RuntimeResourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeResourceSyncService.class);

    private final ResourcePatternResolver resourceResolver;
    private final Path skillsDir;
    private final Path schedulesDir;

    @Autowired
    public RuntimeResourceSyncService(
            SkillProperties skillProperties,
            ScheduleProperties scheduleProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                normalizeDir(skillProperties == null ? null : skillProperties.getExternalDir()),
                normalizeDir(scheduleProperties == null ? null : scheduleProperties.getExternalDir())
        );
    }

    RuntimeResourceSyncService(
            ScheduleProperties scheduleProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                null,
                normalizeDir(scheduleProperties == null ? null : scheduleProperties.getExternalDir())
        );
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path schedulesDir
    ) {
        this(resourceResolver, null, schedulesDir);
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path skillsDir,
            Path schedulesDir
    ) {
        this.resourceResolver = resourceResolver;
        this.skillsDir = normalizeDir(skillsDir);
        this.schedulesDir = normalizeDir(schedulesDir);
    }

    @PostConstruct
    public void syncRuntimeDirectories() {
        syncClasspathResourceDirectory("skills", skillsDir);
        syncResourceDirectory("schedules", schedulesDir);
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

    private static Path normalizeDir(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private static Path normalizeDir(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

}
