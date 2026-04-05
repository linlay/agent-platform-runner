package com.linlay.agentplatform.chat.event;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SseEventFactory {

    private SseEventFactory() {
    }

    public static Map<String, Object> event(String type, long timestamp, long seq, Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("type", type);
        data.put("timestamp", timestamp);
        if (payload != null && !payload.isEmpty()) {
            data.putAll(payload);
        }
        return data;
    }
}
