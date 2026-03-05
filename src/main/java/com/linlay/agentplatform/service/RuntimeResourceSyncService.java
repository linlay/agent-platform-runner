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

import com.linlay.agentplatform.config.CapabilityCatalogProperties;
import com.linlay.agentplatform.skill.SkillCatalogProperties;

import jakarta.annotation.PostConstruct;

@Component
public class RuntimeResourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeResourceSyncService.class);

    private final ResourcePatternResolver resourceResolver;
    private final Path toolsDir;
    private final Path skillsDir;

    @Autowired
    public RuntimeResourceSyncService(
            CapabilityCatalogProperties capabilityCatalogProperties,
            SkillCatalogProperties skillCatalogProperties
    ) {
        this(
                new PathMatchingResourcePatternResolver(),
                Path.of(capabilityCatalogProperties.getToolsExternalDir()).toAbsolutePath().normalize(),
                Path.of(skillCatalogProperties.getExternalDir()).toAbsolutePath().normalize()
        );
    }

    RuntimeResourceSyncService(
            ResourcePatternResolver resourceResolver,
            Path toolsDir,
            Path skillsDir
    ) {
        this.resourceResolver = resourceResolver;
        this.toolsDir = toolsDir;
        this.skillsDir = skillsDir;
    }

    @PostConstruct
    public void syncRuntimeDirectories() {
        syncResourceDirectory("tools", toolsDir);
        syncResourceDirectory("skills", skillsDir);
    }

    private void syncResourceDirectory(String resourceDir, Path targetDir) {
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

}
