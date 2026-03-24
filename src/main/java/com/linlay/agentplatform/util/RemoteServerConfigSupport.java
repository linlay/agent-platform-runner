package com.linlay.agentplatform.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class RemoteServerConfigSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private RemoteServerConfigSupport() {
    }

    public static List<ServerSpec> loadServerSpecs(Path dir, ObjectMapper objectMapper, Logger log) {
        if (dir == null || objectMapper == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<ServerSpec> loaded = new ArrayList<>();
        for (Path file : YamlCatalogSupport.selectYamlFiles(YamlCatalogSupport.listRegularFiles(dir, log), "remote server", log)) {
            loaded.addAll(parseServerFile(file, objectMapper, log));
        }
        return List.copyOf(loaded);
    }

    public static List<ServerSpec> parseServerFile(Path file, ObjectMapper objectMapper, Logger log) {
        try {
            String raw = Files.readString(file);
            YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                    raw,
                    List.of("serverkey", "baseurl", "endpointpath")
            );
            if (headerError.isPresent()) {
                throw new IllegalStateException("Invalid header: " + headerError.value());
            }
            JsonNode root = YAML_MAPPER.readTree(raw);
            if (root != null && root.isObject()) {
                return List.of(toServerSpec(root, objectMapper));
            }
        } catch (Exception ex) {
            log.warn("Skip invalid remote server config file: {}", file, ex);
        }
        return List.of();
    }

    public static String normalizeKey(String raw) {
        String text = normalizeText(raw);
        return StringUtils.hasText(text) ? text.toLowerCase(Locale.ROOT) : "";
    }

    public static String normalizeText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static String normalizeEndpointPath(String raw, String defaultPath) {
        String normalized = normalizeText(raw);
        if (!StringUtils.hasText(normalized)) {
            normalized = StringUtils.hasText(defaultPath) ? defaultPath.trim() : "/mcp";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    public static Map<String, String> normalizeStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeText(entry.getKey());
            String value = normalizeText(entry.getValue());
            if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                continue;
            }
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    public static Map<String, String> normalizeAliasMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String alias = normalizeText(entry.getKey()).toLowerCase(Locale.ROOT);
            String target = normalizeText(entry.getValue()).toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(alias) || !StringUtils.hasText(target)) {
                continue;
            }
            normalized.put(alias, target);
        }
        return Map.copyOf(normalized);
    }

    public static Integer parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ServerSpec toServerSpec(JsonNode node, ObjectMapper objectMapper) {
        Map<String, Object> raw = objectMapper.convertValue(node, MAP_TYPE);
        return new ServerSpec(
                stringValue(firstNonNull(raw.get("serverKey"), raw.get("server-key"), raw.get("key"))),
                stringValue(firstNonNull(raw.get("baseUrl"), raw.get("base-url"), raw.get("url"))),
                stringValue(firstNonNull(raw.get("endpointPath"), raw.get("endpoint-path"), raw.get("path"))),
                raw.get("enabled") == null || Boolean.parseBoolean(String.valueOf(raw.get("enabled"))),
                stringValue(firstNonNull(raw.get("toolPrefix"), raw.get("tool-prefix"))),
                parseInt(firstNonNull(raw.get("connectTimeoutMs"), raw.get("connect-timeout-ms"))),
                parseInt(firstNonNull(raw.get("readTimeoutMs"), raw.get("read-timeout-ms"))),
                parseInt(raw.get("retry")),
                toStringMap(raw.get("headers")),
                toStringMap(raw.get("aliasMap"))
        );
    }

    private static Map<String, String> toStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            normalized.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record ServerSpec(
            String serverKey,
            String baseUrl,
            String endpointPath,
            boolean enabled,
            String toolPrefix,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            Integer retry,
            Map<String, String> headers,
            Map<String, String> aliasMap
    ) {
        public ServerSpec {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            aliasMap = aliasMap == null ? Map.of() : Map.copyOf(aliasMap);
        }

        public Optional<String> normalizedServerKey() {
            String normalized = normalizeKey(serverKey);
            return StringUtils.hasText(normalized) ? Optional.of(normalized) : Optional.empty();
        }
    }
}
