package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.sandbox.MountAccessMode;
import com.linlay.agentplatform.agent.runtime.sandbox.SandboxLevel;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.model.ModelProtocol;

import java.nio.file.Path;
import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        Object icon,
        String description,
        String role,
        String modelKey,
        String providerKey,
        String model,
        ModelProtocol protocol,
        AgentRuntimeMode mode,
        RunSpec runSpec,
        AgentMode agentMode,
        List<String> tools,
        List<String> skills,
        List<AgentControl> controls,
        SandboxConfig sandboxConfig,
        List<String> modelKeys,
        String soulContent,
        String agentsContent,
        List<String> contextTags,
        List<String> perAgentSkills,
        Path agentDir,
        MemoryConfig memoryConfig
) {
    public record MemoryConfig(boolean enabled) {
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            List<AgentControl> controls,
            SandboxConfig sandboxConfig,
            List<String> modelKeys,
            String soulContent,
            String agentsContent,
            List<String> contextTags,
            List<String> perAgentSkills,
            Path agentDir
    ) {
        this(
                id,
                name,
                icon,
                description,
                role,
                modelKey,
                providerKey,
                model,
                protocol,
                mode,
                runSpec,
                agentMode,
                tools,
                skills,
                controls,
                sandboxConfig,
                modelKeys,
                soulContent,
                agentsContent,
                contextTags,
                perAgentSkills,
                agentDir,
                null
        );
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            List<AgentControl> controls,
            SandboxConfig sandboxConfig,
            List<String> modelKeys,
            String soulContent,
            String agentsContent,
            List<String> perAgentSkills,
            Path agentDir
    ) {
        this(
                id,
                name,
                icon,
                description,
                role,
                modelKey,
                providerKey,
                model,
                protocol,
                mode,
                runSpec,
                agentMode,
                tools,
                skills,
                controls,
                sandboxConfig,
                modelKeys,
                soulContent,
                agentsContent,
                List.of(),
                perAgentSkills,
                agentDir,
                null
            );
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String providerKey,
            String model,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills
    ) {
        this(id, name, icon, description, role, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, List.of(), null, List.of(), null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String providerKey,
            String model,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills
    ) {
        this(id, name, icon, description, name, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, List.of(), null, List.of(), null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills
    ) {
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, List.of(), null, List.of(), null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            List<String> modelKeys
    ) {
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, List.of(), null, modelKeys, null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String providerKey,
            String model,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            SandboxConfig sandboxConfig,
            List<String> modelKeys
    ) {
        this(id, name, icon, description, role, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, List.of(), sandboxConfig, modelKeys, null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String providerKey,
            String model,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            SandboxConfig sandboxConfig,
            List<String> modelKeys
    ) {
        this(id, name, icon, description, name, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, List.of(), sandboxConfig, modelKeys, null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition(
            String id,
            String name,
            Object icon,
            String description,
            String role,
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            AgentRuntimeMode mode,
            RunSpec runSpec,
            AgentMode agentMode,
            List<String> tools,
            List<String> skills,
            SandboxConfig sandboxConfig,
            List<String> modelKeys
    ) {
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, List.of(), sandboxConfig, modelKeys, null, null, List.of(), List.of(), null, null);
    }

    public AgentDefinition {
        if (role == null || role.isBlank()) {
            role = (name == null || name.isBlank()) ? id : name;
        }
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
        if (skills == null) {
            skills = List.of();
        } else {
            skills = List.copyOf(skills);
        }
        if (controls == null) {
            controls = List.of();
        } else {
            controls = List.copyOf(controls);
        }
        if (sandboxConfig == null) {
            sandboxConfig = new SandboxConfig(null, null, List.of());
        }
        if (modelKeys == null) {
            modelKeys = List.of();
        } else {
            modelKeys = List.copyOf(modelKeys);
        }
        soulContent = normalizeOptionalText(soulContent);
        agentsContent = normalizeOptionalText(agentsContent);
        contextTags = RuntimeContextTags.normalize(contextTags);
        if (memoryConfig == null) {
            memoryConfig = new MemoryConfig(false);
        }
        if (perAgentSkills == null) {
            perAgentSkills = List.of();
        } else {
            perAgentSkills = List.copyOf(perAgentSkills);
        }
        if (agentDir != null) {
            agentDir = agentDir.toAbsolutePath().normalize();
        }
        if (protocol == null) {
            protocol = ModelProtocol.OPENAI;
        }
    }

    public String systemPrompt() {
        return agentMode.primarySystemPrompt();
    }

    public boolean memoryEnabled() {
        return memoryConfig != null && memoryConfig.enabled();
    }

    public record SandboxConfig(
            String environmentId,
            SandboxLevel level,
            List<ExtraMount> extraMounts
    ) {
        public SandboxConfig {
            environmentId = environmentId == null || environmentId.isBlank() ? null : environmentId.trim();
            extraMounts = extraMounts == null ? List.of() : List.copyOf(extraMounts);
        }

        public SandboxConfig(String environmentId) {
            this(environmentId, null, List.of());
        }

        public SandboxConfig(String environmentId, SandboxLevel level) {
            this(environmentId, level, List.of());
        }
    }

    public record ExtraMount(
            String platform,
            String source,
            String destination,
            MountAccessMode mode
    ) {
        public ExtraMount {
            platform = normalizeValue(platform);
            source = normalizeValue(source);
            destination = normalizeValue(destination);
        }

        public ExtraMount(String platform, String source, String destination) {
            this(platform, source, destination, null);
        }

        public boolean isPlatform() {
            return platform != null;
        }

        private static String normalizeValue(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
