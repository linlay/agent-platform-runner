package com.linlay.agentplatform.agent;

import java.nio.file.Path;
import java.time.LocalDate;

public final class ExampleAgentDirectoryGeneratorMain {

    private ExampleAgentDirectoryGeneratorMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: ExampleAgentDirectoryGeneratorMain <sourceDir> <targetDir> [yyyy-MM-dd]"
            );
        }
        Path sourceDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path targetDir = Path.of(args[1]).toAbsolutePath().normalize();
        LocalDate scaffoldDate = args.length == 3 ? LocalDate.parse(args[2]) : LocalDate.now();
        new ExampleAgentDirectoryGenerator().generateAll(sourceDir, targetDir, scaffoldDate);
    }
}
