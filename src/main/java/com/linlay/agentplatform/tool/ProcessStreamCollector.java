package com.linlay.agentplatform.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class ProcessStreamCollector implements Runnable {

    private final InputStream stream;
    private final int maxChars;
    private final StringBuilder out = new StringBuilder();
    private boolean truncated;

    ProcessStreamCollector(InputStream stream, int maxChars) {
        this.stream = stream;
        this.maxChars = Math.max(256, maxChars);
    }

    @Override
    public void run() {
        if (stream == null) {
            return;
        }
        try (InputStream input = stream; InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) >= 0) {
                append(buffer, len);
            }
        } catch (IOException ignored) {
            // Swallow stream read errors to avoid masking tool execution result.
        }
    }

    String text() {
        if (!truncated) {
            return out.toString();
        }
        return out + "\n[TRUNCATED]";
    }

    private void append(char[] chars, int len) {
        if (len <= 0) {
            return;
        }
        if (out.length() >= maxChars) {
            truncated = true;
            return;
        }
        int remain = maxChars - out.length();
        int toWrite = Math.min(remain, len);
        out.append(chars, 0, toWrite);
        if (toWrite < len) {
            truncated = true;
        }
    }
}
