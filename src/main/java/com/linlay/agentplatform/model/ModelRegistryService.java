package com.linlay.agentplatform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.config.ProviderRegistryService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final ModelProperties properties;
    private final ProviderRegistryService providerRegistryService;

    private final Object reloadLock = new Object();
    private volatile Map<String, ModelDefinition> byKey = Map.of();

    public ModelRegistryService(
            ObjectMapper objectMapper,
            ModelProperties properties,
            ProviderRegistryService providerRegistryService
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        this.providerRegistryService = providerRegistryService;
        refreshModels();
    }

    public List<ModelDefinition> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(ModelDefinition::key))
                .toList();
    }

    public Optional<ModelDefinition> find(String modelKey) {
        if (!StringUtils.hasText(modelKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(normalize(modelKey)));
    }

    public Set<String> findModelKeysByProviders(Set<String> providerKeys) {
        if (providerKeys == null || providerKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> normalizedProviderKeys = providerKeys.stream()
                .map(StringHelpers::normalizeKey)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (normalizedProviderKeys.isEmpty()) {
            return Set.of();
        }
        return byKey.values().stream()
                .filter(model -> normalizedProviderKeys.contains(normalize(model.provider())))
                .map(ModelDefinition::key)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public CatalogDiff refreshModels() {
        synchronized (reloadLock) {
            Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
            Map<String, ModelDefinition> before = byKey;
            Map<String, ModelDefinition> loaded = new LinkedHashMap<>();

            if (!Files.exists(dir)) {
                byKey = Map.of();
                return CatalogDiff.between(before, byKey);
            }
            if (!Files.isDirectory(dir)) {
                log.warn("Configured models directory is not a directory: {}", dir);
                byKey = Map.of();
                return CatalogDiff.between(before, byKey);
            }

            YamlCatalogSupport.selectYamlFiles(YamlCatalogSupport.listRegularFiles(dir, log), "model", log)
                    .forEach(path -> tryLoad(path).ifPresent(model -> {
                        if (loaded.containsKey(model.key())) {
                            log.warn("Duplicate model key '{}' found in {}, keep the first one", model.key(), path);
                            return;
                        }
                        loaded.put(model.key(), model);
                    }));

            byKey = Map.copyOf(loaded);
            CatalogDiff diff = CatalogDiff.between(before, byKey);
            log.debug("Refreshed model registry, size={}, changed={}", loaded.size(), diff.changedKeys().size());
            return diff;
        }
    }

    private Optional<ModelDefinition> tryLoad(Path file) {
        String fileBasedKey = YamlCatalogSupport.fileBaseName(file).trim();
        if (fileBasedKey.isBlank()) {
            log.warn("Skip model file with empty name: {}", file);
            return Optional.empty();
        }

        try {
            String raw = Files.readString(file);
            YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                    raw,
                    List.of("key", "provider", "protocol", "modelid")
            );
            if (headerError.isPresent()) {
                log.warn("Skip model '{}' due to invalid header: {} ({})", fileBasedKey, headerError.value(), file);
                return Optional.empty();
            }
            JsonNode root = yamlMapper.readTree(raw);

            String key = normalize(root.path("key").asText(fileBasedKey));
            if (key.isBlank()) {
                log.warn("Skip model without key: {}", file);
                return Optional.empty();
            }

            String provider = normalize(root.path("provider").asText(""));
            if (provider.isBlank()) {
                log.warn("Skip model '{}' without provider: {}", key, file);
                return Optional.empty();
            }
            if (providerRegistryService == null || providerRegistryService.find(provider).isEmpty()) {
                log.warn("Skip model '{}' with unknown provider '{}': {}", key, provider, file);
                return Optional.empty();
            }

            String modelId = root.path("modelId").asText("").trim();
            if (modelId.isBlank()) {
                log.warn("Skip model '{}' without modelId: {}", key, file);
                return Optional.empty();
            }

            ModelProtocol protocol;
            try {
                protocol = ModelProtocol.fromJson(root.path("protocol").asText("OPENAI"));
            } catch (IllegalArgumentException ex) {
                log.warn("Skip model '{}' with unsupported protocol in {}", key, file, ex);
                return Optional.empty();
            }
            if (protocol == ModelProtocol.ANTHROPIC) {
                log.warn("Skip model '{}' with protocol '{}' because this protocol is not implemented yet: {}", key, protocol, file);
                return Optional.empty();
            }

            ModelDefinition.Pricing pricing = parsePricing(root.path("pricing"));

            return Optional.of(new ModelDefinition(
                    key,
                    provider,
                    protocol,
                    modelId,
                    root.path("isReasoner").asBoolean(false),
                    root.path("isFunction").asBoolean(false),
                    optionalInt(root, "maxTokens"),
                    optionalInt(root, "maxInputTokens"),
                    optionalInt(root, "maxOutputTokens"),
                    pricing,
                    file.toString()
            ));
        } catch (Exception ex) {
            log.warn("Skip invalid model file: {}", file, ex);
            return Optional.empty();
        }
    }

    private ModelDefinition.Pricing parsePricing(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new ModelDefinition.Pricing(0, 0, 0, 1.0, List.of());
        }

        List<ModelDefinition.Tier> tiers = new ArrayList<>();
        JsonNode tiersNode = node.path("tiers");
        if (tiersNode.isArray()) {
            for (JsonNode tierNode : tiersNode) {
                if (!tierNode.isObject()) {
                    continue;
                }
                tiers.add(new ModelDefinition.Tier(
                        optionalInt(tierNode, "minInputTokens"),
                        optionalInt(tierNode, "maxInputTokens"),
                        optionalInt(tierNode, "promptPointsPer1k"),
                        optionalInt(tierNode, "completionPointsPer1k"),
                        optionalInt(tierNode, "perCallPoints"),
                        optionalDouble(tierNode, "priceRatio")
                ));
            }
        }

        return new ModelDefinition.Pricing(
                optionalInt(node, "promptPointsPer1k"),
                optionalInt(node, "completionPointsPer1k"),
                optionalInt(node, "perCallPoints"),
                optionalDouble(node, "priceRatio"),
                tiers
        );
    }

    private Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.intValue();
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.doubleValue();
    }

    private String normalize(String raw) {
        return StringHelpers.normalizeKey(raw);
    }
}
