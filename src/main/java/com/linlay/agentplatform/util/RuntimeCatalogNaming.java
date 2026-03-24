package com.linlay.agentplatform.util;

import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;

public final class RuntimeCatalogNaming {

    private static final String EXAMPLE_SUFFIX = ".example";
    private static final String DEMO_SUFFIX = ".demo";

    private RuntimeCatalogNaming() {
    }

    public static boolean shouldLoadRuntimeName(String rawName) {
        return StringUtils.hasText(rawName) && !isExampleName(rawName);
    }

    public static boolean shouldLoadRuntimePath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        return shouldLoadRuntimeName(path.getFileName().toString());
    }

    public static boolean isExampleName(String rawName) {
        return hasMarker(rawName, EXAMPLE_SUFFIX);
    }

    public static boolean isDemoName(String rawName) {
        return hasMarker(rawName, DEMO_SUFFIX);
    }

    public static String logicalBaseName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return "";
        }
        String name = rawName.trim();
        int lastDot = name.lastIndexOf('.');
        String stem = lastDot > 0 ? name.substring(0, lastDot) : name;
        String lowerStem = stem.toLowerCase(Locale.ROOT);
        if (lowerStem.endsWith(EXAMPLE_SUFFIX)) {
            return stem.substring(0, stem.length() - EXAMPLE_SUFFIX.length());
        }
        if (lowerStem.endsWith(DEMO_SUFFIX)) {
            return stem.substring(0, stem.length() - DEMO_SUFFIX.length());
        }
        return stem;
    }

    private static boolean hasMarker(String rawName, String marker) {
        if (!StringUtils.hasText(rawName)) {
            return false;
        }
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(marker)) {
            return true;
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot <= 0) {
            return false;
        }
        return name.substring(0, lastDot).endsWith(marker);
    }
}
