package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.ViewportType;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ViewportRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ViewportRegistryService.class);
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".html", ".qlc");
    private static final String CLASSPATH_PATTERN = "classpath*:/viewports/*";

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourcePatternResolver;
    private final java.nio.file.Path legacyViewportDir;

    private final Object refreshLock = new Object();
    private volatile Map<String, ViewportEntry> byKey = Map.of();

    @Autowired
    public ViewportRegistryService(ObjectMapper objectMapper) {
        this(objectMapper, new PathMatchingResourcePatternResolver(), null);
    }

    ViewportRegistryService(ObjectMapper objectMapper, ResourcePatternResolver resourcePatternResolver) {
        this(objectMapper, resourcePatternResolver, null);
    }

    ViewportRegistryService(ObjectMapper objectMapper, ResourcePatternResolver resourcePatternResolver, java.nio.file.Path legacyViewportDir) {
        this.objectMapper = objectMapper;
        this.resourcePatternResolver = resourcePatternResolver;
        this.legacyViewportDir = legacyViewportDir;
        refreshViewports();
    }

    public Optional<ViewportEntry> find(String viewportKey) {
        if (!StringUtils.hasText(viewportKey)) {
            return Optional.empty();
        }
        String normalized = normalizeKey(viewportKey);
        return Optional.ofNullable(byKey.get(normalized));
    }

    public void refreshViewports() {
        synchronized (refreshLock) {
            Map<String, ViewportEntry> updated = new LinkedHashMap<>();
            if (legacyViewportDir != null) {
                for (Resource resource : fileResources()) {
                    tryLoad(resource).ifPresent(entry -> updated.putIfAbsent(entry.viewportKey(), entry));
                }
            } else {
                for (Resource resource : classpathResources()) {
                    tryLoad(resource).ifPresent(entry -> {
                        String key = entry.viewportKey();
                        if (updated.containsKey(key)) {
                            log.warn("Duplicate viewportKey '{}' detected, skip resource {}", key, resource);
                            return;
                        }
                        updated.put(key, entry);
                    });
                }
            }
            byKey = Map.copyOf(updated);
            log.debug("Refreshed classpath viewport registry, size={}", updated.size());
        }
    }

    private List<Resource> fileResources() {
        if (legacyViewportDir == null || !java.nio.file.Files.isDirectory(legacyViewportDir)) {
            return List.of();
        }
        return YamlCatalogSupport.listRegularFiles(legacyViewportDir, log).stream()
                .filter(path -> {
                    String fileName = path.getFileName().toString();
                    return resolveSuffix(fileName) != null;
                })
                .map(path -> (Resource) new org.springframework.core.io.FileSystemResource(path))
                .toList();
    }

    private List<Resource> classpathResources() {
        try {
            Resource[] resources = resourcePatternResolver.getResources(CLASSPATH_PATTERN);
            return java.util.Arrays.stream(resources)
                    .filter(resource -> resource != null && resource.exists() && resource.isReadable())
                    .filter(this::isSupported)
                    .sorted(Comparator.comparing(this::resourceSortKey))
                    .toList();
        } catch (IOException ex) {
            log.warn("Cannot list viewport resources from {}", CLASSPATH_PATTERN, ex);
            return List.of();
        }
    }

    private Optional<ViewportEntry> tryLoad(Resource resource) {
        String fileName = resource.getFilename();
        if (!StringUtils.hasText(fileName)) {
            return Optional.empty();
        }
        String suffix = resolveSuffix(fileName);
        if (suffix == null) {
            return Optional.empty();
        }
        String baseName = fileName.substring(0, fileName.length() - suffix.length());
        String viewportKey = normalizeKey(baseName);
        if (viewportKey.isBlank()) {
            log.warn("Skip viewport resource with empty key: {}", resource);
            return Optional.empty();
        }

        ViewportType type = resolveType(suffix);
        if (type == null) {
            return Optional.empty();
        }

        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            Object payload = type == ViewportType.HTML
                    ? raw
                    : objectMapper.readValue(raw, Object.class);
            return Optional.of(new ViewportEntry(viewportKey, type, payload));
        } catch (Exception ex) {
            log.warn("Skip invalid viewport resource: {}", resource, ex);
            return Optional.empty();
        }
    }

    private boolean isSupported(Resource resource) {
        String fileName = resource.getFilename();
        return StringUtils.hasText(fileName) && resolveSuffix(fileName) != null;
    }

    private String resolveSuffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SUPPORTED_SUFFIXES.stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .filter(lower::endsWith)
                .findFirst()
                .orElse(null);
    }

    private ViewportType resolveType(String suffix) {
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case ".html" -> ViewportType.HTML;
            case ".qlc" -> ViewportType.QLC;
            default -> null;
        };
    }

    private String normalizeKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private String resourceSortKey(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException ex) {
            return String.valueOf(resource);
        }
    }

    public record ViewportEntry(
            String viewportKey,
            ViewportType viewportType,
            Object payload
    ) {
    }
}
