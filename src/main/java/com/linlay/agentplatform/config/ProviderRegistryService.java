package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.service.CatalogDiff;
import com.linlay.agentplatform.util.StringHelpers;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ProviderRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);

    private final ObjectMapper yamlMapper;
    private final ProviderProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, ProviderConfig> byKey = Map.of();

    public ProviderRegistryService(
            ProviderProperties properties
    ) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        refreshProviders();
    }

    public List<ProviderConfig> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(ProviderConfig::key))
                .toList();
    }

    public Optional<ProviderConfig> find(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(StringHelpers.normalizeKey(providerKey)));
    }

    public CatalogDiff refreshProviders() {
        synchronized (reloadLock) {
            Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
            Map<String, ProviderConfig> before = byKey;

            if (!Files.exists(dir)) {
                byKey = Map.of();
                return CatalogDiff.between(before, byKey);
            }
            if (!Files.isDirectory(dir)) {
                log.warn("Configured providers directory is not a directory: {}", dir);
                return CatalogDiff.between(before, before);
            }

            Map<String, ProviderConfig> loaded = new LinkedHashMap<>();
            try {
                for (Path path : YamlCatalogSupport.selectYamlFiles(
                        YamlCatalogSupport.listRegularFiles(dir, log),
                        "provider",
                        log
                )) {
                    ProviderConfig provider = tryLoad(path).orElse(null);
                    if (provider == null) {
                        continue;
                    }
                    ProviderConfig previous = loaded.putIfAbsent(provider.key(), provider);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate provider key '" + provider.key() + "' detected in " + dir
                        );
                    }
                }
            } catch (RuntimeException ex) {
                if (before.isEmpty()) {
                    throw ex;
                }
                log.warn("Failed to refresh provider registry, keep previous snapshot: {}", dir, ex);
                return CatalogDiff.between(before, before);
            }

            byKey = Map.copyOf(loaded);
            CatalogDiff diff = CatalogDiff.between(before, byKey);
            log.debug("Refreshed provider registry, size={}, changed={}", loaded.size(), diff.changedKeys().size());
            return diff;
        }
    }

    private Optional<ProviderConfig> tryLoad(Path file) {
        String fileBasedKey = YamlCatalogSupport.fileBaseName(file).trim();
        if (fileBasedKey.isBlank()) {
            log.warn("Skip provider file with empty name: {}", file);
            return Optional.empty();
        }

        try {
            String raw = Files.readString(file);
            YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                    raw,
                    List.of("key", "baseurl")
            );
            if (headerError.isPresent()) {
                throw new IllegalStateException(
                        "Skip provider '%s' due to invalid header: %s (%s)"
                                .formatted(fileBasedKey, headerError.value(), file)
                );
            }

            JsonNode root = yamlMapper.readTree(raw);
            String key = StringHelpers.normalizeKey(root.path("key").asText(fileBasedKey));
            if (!StringUtils.hasText(key)) {
                throw new IllegalStateException("Skip provider without key: " + file);
            }

            String baseUrl = StringHelpers.trimToEmpty(root.path("baseUrl").asText(""));
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalStateException("Skip provider '" + key + "' without baseUrl: " + file);
            }
            if (root.has("model")) {
                throw new IllegalStateException(
                        "Skip provider '%s' because legacy field 'model' is no longer supported; use 'defaultModel' instead: %s"
                                .formatted(key, file)
                );
            }

            Map<ModelProtocol, ProtocolConfig> protocols = new LinkedHashMap<>();
            JsonNode protocolsNode = root.path("protocols");
            if (protocolsNode.isObject()) {
                protocolsNode.fields().forEachRemaining(entry -> {
                    try {
                        ModelProtocol protocol = ModelProtocol.fromJson(entry.getKey());
                        String endpointPath = StringHelpers.trimToEmpty(entry.getValue().path("endpointPath").asText(""));
                        protocols.put(protocol, new ProtocolConfig(endpointPath));
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalStateException(
                                "Skip provider '%s' with unsupported protocol '%s': %s"
                                        .formatted(key, entry.getKey(), file),
                                ex
                        );
                    }
                });
            }

            return Optional.of(new ProviderConfig(
                    key,
                    baseUrl,
                    StringHelpers.trimToEmpty(root.path("apiKey").asText("")),
                    StringHelpers.trimToEmpty(root.path("defaultModel").asText("")),
                    protocols
            ));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse provider config file: " + file, ex);
        }
    }
}
