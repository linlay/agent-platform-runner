package com.linlay.agentplatform.util;

import java.util.Map;

public final class MapReaders {

    private MapReaders() {
    }

    public static String readString(Map<?, ?> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text == null || text.isBlank() ? null : text;
    }
}
