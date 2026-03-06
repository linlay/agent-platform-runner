package com.linlay.agentplatform.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class TestSystemBashFactory {

    private TestSystemBashFactory() {
    }

    public static SystemBash defaultBash() {
        return bash(
                Path.of(System.getProperty("user.dir", ".")),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                "bash",
                10_000,
                16_000
        );
    }

    public static SystemBash bash(
            Path workingDirectory,
            List<Path> additionalAllowedRoots,
            Set<String> allowedCommands,
            Set<String> pathCheckedCommands
    ) {
        return bash(
                workingDirectory,
                additionalAllowedRoots,
                allowedCommands,
                pathCheckedCommands,
                Set.of(),
                false,
                "bash",
                10_000,
                16_000
        );
    }

    public static SystemBash bash(
            Path workingDirectory,
            List<Path> additionalAllowedRoots,
            Set<String> allowedCommands,
            Set<String> pathCheckedCommands,
            boolean shellFeaturesEnabled,
            String shellExecutable,
            int timeoutMs,
            int maxCommandChars
    ) {
        return bash(
                workingDirectory,
                additionalAllowedRoots,
                allowedCommands,
                pathCheckedCommands,
                Set.of(),
                shellFeaturesEnabled,
                shellExecutable,
                timeoutMs,
                maxCommandChars
        );
    }

    public static SystemBash bash(
            Path workingDirectory,
            List<Path> additionalAllowedRoots,
            Set<String> allowedCommands,
            Set<String> pathCheckedCommands,
            Set<String> pathCheckBypassCommands,
            boolean shellFeaturesEnabled,
            String shellExecutable,
            int timeoutMs,
            int maxCommandChars
    ) {
        return new SystemBash(
                workingDirectory,
                additionalAllowedRoots,
                allowedCommands,
                pathCheckedCommands,
                pathCheckBypassCommands,
                shellFeaturesEnabled,
                shellExecutable,
                timeoutMs,
                maxCommandChars
        );
    }
}
