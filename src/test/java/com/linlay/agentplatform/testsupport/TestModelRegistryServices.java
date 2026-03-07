package com.linlay.agentplatform.testsupport;

import com.linlay.agentplatform.model.ModelDefinition;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.model.ModelRegistryService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestModelRegistryServices {

    private TestModelRegistryServices() {
    }

    public static ModelRegistryService standardRegistry() {
        return registry(
                model("bailian-qwen3-max", "bailian", "qwen3-max", ModelProtocol.OPENAI, false, true),
                model("bailian-qwen3_5-plus", "bailian", "qwen3.5-plus", ModelProtocol.OPENAI, true, true),
                model("siliconflow-deepseek-v3_2", "siliconflow", "deepseek-ai/DeepSeek-V3.2", ModelProtocol.OPENAI, true, true)
        );
    }

    public static ModelRegistryService registry(ModelDefinition... definitions) {
        Map<String, ModelDefinition> byKey = new LinkedHashMap<>();
        if (definitions != null) {
            for (ModelDefinition definition : definitions) {
                if (definition != null) {
                    byKey.put(normalize(definition.key()), definition);
                }
            }
        }
        ModelRegistryService registry = mock(ModelRegistryService.class);
        when(registry.find(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return Optional.ofNullable(byKey.get(normalize(key)));
        });
        when(registry.list()).thenReturn(byKey.values().stream().toList());
        return registry;
    }

    public static ModelDefinition model(
            String key,
            String provider,
            String modelId,
            ModelProtocol protocol,
            boolean isReasoner,
            boolean isFunction
    ) {
        return new ModelDefinition(
                key,
                provider,
                protocol,
                modelId,
                isReasoner,
                isFunction,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
