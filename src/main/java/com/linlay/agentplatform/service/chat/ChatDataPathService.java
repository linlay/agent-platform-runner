package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.util.DataFilePathNormalizer;
import com.linlay.agentplatform.util.StringHelpers;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ChatDataPathService {

    private final Path dataDir;

    public ChatDataPathService(ChatStorageProperties properties) {
        this.dataDir = Path.of(properties.getDir()).toAbsolutePath().normalize();
    }

    public Path dataDir() {
        return dataDir;
    }

    public String normalizeChatId(String chatId) {
        if (!StringHelpers.isValidChatId(chatId)) {
            throw new IllegalArgumentException("chatId must not be empty");
        }
        return chatId.trim();
    }

    public Path resolveChatDir(String chatId) {
        String normalizedChatId = normalizeChatId(chatId);
        return dataDir.resolve(normalizedChatId).normalize();
    }

    public boolean isChatAssetPath(String normalizedFilePath) {
        String normalized = DataFilePathNormalizer.normalizeFileParam(normalizedFilePath);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        try {
            Path path = Path.of(normalized);
            return path.getNameCount() >= 2 && StringHelpers.isValidChatId(path.getName(0).toString());
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean belongsToChat(String chatId, String normalizedFilePath) {
        if (!StringHelpers.isValidChatId(chatId)) {
            return false;
        }
        String normalized = DataFilePathNormalizer.normalizeFileParam(normalizedFilePath);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        try {
            Path path = Path.of(normalized);
            if (path.getNameCount() < 2) {
                return false;
            }
            return normalizeChatId(chatId).equals(normalizeChatId(path.getName(0).toString()));
        } catch (Exception ex) {
            return false;
        }
    }

    public String toChatAssetPath(String chatId, String relativePath) {
        String normalizedChatId = normalizeChatId(chatId);
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        return normalizedChatId + "/" + normalizedRelativePath;
    }

    public String toAssetUrl(String chatId, String relativePath) {
        String file = toChatAssetPath(chatId, relativePath);
        String encoded = URLEncoder.encode(file, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/resource?file=" + encoded;
    }

    public String normalizeRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        String trimmed = relativePath.trim();
        if (trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("relativePath must not contain path traversal");
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        Path normalized = Path.of(trimmed).normalize();
        if (normalized.isAbsolute()) {
            throw new IllegalArgumentException("relativePath must be relative");
        }
        String value = normalized.toString().replace('\\', '/');
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        return value;
    }
}
