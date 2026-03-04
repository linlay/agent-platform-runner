package com.linlay.agentplatform.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PathCheckedArgumentResolver {

    private static final Set<String> GIT_GLOBAL_PATH_OPTIONS = Set.of(
            "-C", "--git-dir", "--work-tree", "--exec-path"
    );

    private static final Set<String> GIT_GLOBAL_VALUE_OPTIONS = Set.of(
            "-c", "--config-env", "--namespace", "--super-prefix"
    );

    private static final Set<String> GIT_INIT_PATH_OPTIONS = Set.of(
            "--template", "--separate-git-dir"
    );

    private static final Set<String> GIT_CLONE_PATH_OPTIONS = Set.of(
            "--template", "--reference", "--reference-if-able", "--separate-git-dir"
    );

    private static final Set<String> GIT_CLONE_VALUE_OPTIONS = Set.of(
            "-o", "--origin",
            "-b", "--branch",
            "-c", "--config",
            "-j", "--jobs",
            "-u", "--upload-pack",
            "--depth",
            "--shallow-since",
            "--shallow-exclude",
            "--filter",
            "--server-option",
            "--bundle-uri"
    );

    private PathCheckedArgumentResolver() {
    }

    static List<String> collectPathArguments(String commandName, List<String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        if ("git".equals(commandName)) {
            return collectGitPathArguments(args);
        }

        List<String> pathArguments = new ArrayList<>();
        for (String arg : args) {
            if (arg == null || arg.isBlank() || arg.startsWith("-")) {
                continue;
            }
            pathArguments.add(arg);
        }
        return pathArguments;
    }

    private static List<String> collectGitPathArguments(List<String> args) {
        List<String> pathArguments = new ArrayList<>();
        int index = 0;

        while (index < args.size()) {
            String token = args.get(index);
            if (token == null || token.isBlank()) {
                index++;
                continue;
            }
            if ("--".equals(token)) {
                index++;
                break;
            }
            if (!isOption(token)) {
                break;
            }

            if (token.startsWith("-C") && token.length() > 2) {
                pathArguments.add(token.substring(2));
                index++;
                continue;
            }
            if (collectLongOptionPathValue(pathArguments, token, "--git-dir")
                    || collectLongOptionPathValue(pathArguments, token, "--work-tree")
                    || collectLongOptionPathValue(pathArguments, token, "--exec-path")) {
                index++;
                continue;
            }
            if (GIT_GLOBAL_PATH_OPTIONS.contains(token)) {
                index = collectNextTokenAsPath(pathArguments, args, index);
                continue;
            }
            if (GIT_GLOBAL_VALUE_OPTIONS.contains(token)) {
                index = skipNextToken(args, index);
                continue;
            }
            index++;
        }

        if (index >= args.size()) {
            return pathArguments;
        }

        String subcommand = args.get(index);
        index++;
        if (subcommand == null || subcommand.isBlank()) {
            return pathArguments;
        }

        switch (subcommand) {
            case "init" -> collectGitInitPathArguments(args, index, pathArguments);
            case "clone" -> collectGitClonePathArguments(args, index, pathArguments);
            default -> collectPathspecAfterDoubleDash(args, index, pathArguments);
        }
        return pathArguments;
    }

    private static void collectGitInitPathArguments(List<String> args, int startIndex, List<String> pathArguments) {
        for (int index = startIndex; index < args.size(); index++) {
            String token = args.get(index);
            if (token == null || token.isBlank()) {
                continue;
            }
            if ("--".equals(token)) {
                if (index + 1 < args.size()) {
                    pathArguments.add(args.get(index + 1));
                }
                return;
            }
            if (collectLongOptionPathValue(pathArguments, token, "--template")
                    || collectLongOptionPathValue(pathArguments, token, "--separate-git-dir")) {
                continue;
            }
            if (GIT_INIT_PATH_OPTIONS.contains(token)) {
                index = collectNextTokenAsPath(pathArguments, args, index) - 1;
                continue;
            }
            if (isOption(token)) {
                continue;
            }

            pathArguments.add(token);
            return;
        }
    }

    private static void collectGitClonePathArguments(List<String> args, int startIndex, List<String> pathArguments) {
        List<String> positionalArguments = new ArrayList<>();
        boolean afterDoubleDash = false;

        for (int index = startIndex; index < args.size(); index++) {
            String token = args.get(index);
            if (token == null || token.isBlank()) {
                continue;
            }
            if (afterDoubleDash) {
                positionalArguments.add(token);
                continue;
            }
            if ("--".equals(token)) {
                afterDoubleDash = true;
                continue;
            }

            if (collectLongOptionPathValue(pathArguments, token, "--template")
                    || collectLongOptionPathValue(pathArguments, token, "--reference")
                    || collectLongOptionPathValue(pathArguments, token, "--reference-if-able")
                    || collectLongOptionPathValue(pathArguments, token, "--separate-git-dir")) {
                continue;
            }
            if (GIT_CLONE_PATH_OPTIONS.contains(token)) {
                index = collectNextTokenAsPath(pathArguments, args, index) - 1;
                continue;
            }
            if (GIT_CLONE_VALUE_OPTIONS.contains(token)) {
                index = skipNextToken(args, index) - 1;
                continue;
            }
            if (isInlineShortOptionWithValue(token, 'b')
                    || isInlineShortOptionWithValue(token, 'c')
                    || isInlineShortOptionWithValue(token, 'j')
                    || isInlineShortOptionWithValue(token, 'o')
                    || isInlineShortOptionWithValue(token, 'u')) {
                continue;
            }
            if (isOption(token)) {
                continue;
            }

            positionalArguments.add(token);
        }

        if (!positionalArguments.isEmpty()) {
            String source = positionalArguments.get(0);
            if (isLikelyLocalPath(source)) {
                pathArguments.add(source);
            }
        }
        if (positionalArguments.size() >= 2) {
            pathArguments.add(positionalArguments.get(1));
        }
    }

    private static void collectPathspecAfterDoubleDash(List<String> args, int startIndex, List<String> pathArguments) {
        boolean afterDoubleDash = false;
        for (int index = startIndex; index < args.size(); index++) {
            String token = args.get(index);
            if (token == null || token.isBlank()) {
                continue;
            }
            if (!afterDoubleDash) {
                if ("--".equals(token)) {
                    afterDoubleDash = true;
                }
                continue;
            }
            pathArguments.add(token);
        }
    }

    private static boolean isOption(String token) {
        return token.startsWith("-") && token.length() > 1;
    }

    private static int collectNextTokenAsPath(List<String> pathArguments, List<String> args, int optionIndex) {
        if (optionIndex + 1 < args.size()) {
            String value = args.get(optionIndex + 1);
            if (value != null && !value.isBlank()) {
                pathArguments.add(value);
            }
            return optionIndex + 2;
        }
        return optionIndex + 1;
    }

    private static int skipNextToken(List<String> args, int optionIndex) {
        if (optionIndex + 1 < args.size()) {
            return optionIndex + 2;
        }
        return optionIndex + 1;
    }

    private static boolean collectLongOptionPathValue(List<String> pathArguments, String token, String optionName) {
        String prefix = optionName + "=";
        if (!token.startsWith(prefix) || token.length() == prefix.length()) {
            return false;
        }
        pathArguments.add(token.substring(prefix.length()));
        return true;
    }

    private static boolean isInlineShortOptionWithValue(String token, char option) {
        return token.length() > 2 && token.charAt(0) == '-' && token.charAt(1) == option;
    }

    private static boolean isLikelyLocalPath(String token) {
        if (token.contains("://") || token.startsWith("git@")) {
            return false;
        }
        if (token.matches("^[^/\\s]+:[^/\\\\].*") && !token.matches("^[A-Za-z]:[\\\\/].*")) {
            return false;
        }
        return true;
    }
}
