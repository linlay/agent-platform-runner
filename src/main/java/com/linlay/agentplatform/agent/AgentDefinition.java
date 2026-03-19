package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
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
        SandboxConfig sandboxConfig,
        List<String> modelKeys,
        String soulContent,
        String agentsContent,
        List<String> perAgentSkills,
        Path agentDir
) {
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
        this(id, name, icon, description, role, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, null, List.of(), null, null, List.of(), null);
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
        this(id, name, icon, description, name, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, null, List.of(), null, null, List.of(), null);
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
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, null, List.of(), null, null, List.of(), null);
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
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, null, modelKeys, null, null, List.of(), null);
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
        this(id, name, icon, description, role, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, sandboxConfig, modelKeys, null, null, List.of(), null);
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
        this(id, name, icon, description, name, null, providerKey, model, null, mode, runSpec, agentMode, tools, skills, sandboxConfig, modelKeys, null, null, List.of(), null);
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
        this(id, name, icon, description, role, modelKey, providerKey, model, protocol, mode, runSpec, agentMode, tools, skills, sandboxConfig, modelKeys, null, null, List.of(), null);
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
        if (sandboxConfig == null) {
            sandboxConfig = new SandboxConfig(null, null);
        }
        if (modelKeys == null) {
            modelKeys = List.of();
        } else {
            modelKeys = List.copyOf(modelKeys);
        }
        soulContent = normalizeOptionalText(soulContent);
        agentsContent = normalizeOptionalText(agentsContent);
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

    public record SandboxConfig(
            String environmentId,
            SandboxLevel level
    ) {
        public SandboxConfig {
            environmentId = environmentId == null || environmentId.isBlank() ? null : environmentId.trim();
        }

        public SandboxConfig(String environmentId) {
            this(environmentId, null);
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
