package com.linlay.agentplatform.agent.runtime;

import java.util.Locale;

public enum MountAccessMode {

    RO(true, "ro"),
    RW(false, "rw");

    private final boolean readOnly;
    private final String yamlValue;

    MountAccessMode(boolean readOnly, String yamlValue) {
        this.readOnly = readOnly;
        this.yamlValue = yamlValue;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public String yamlValue() {
        return yamlValue;
    }

    public static MountAccessMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ro" -> RO;
            case "rw" -> RW;
            default -> null;
        };
    }
}
