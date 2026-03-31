package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.util.DataFilePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatAssetAccessService {

    private static final Logger log = LoggerFactory.getLogger(ChatAssetAccessService.class);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("!?\\[[^\\]]*]\\(([^)]+)\\)");

    private final ChatRecordStore chatRecordStore;
    private final ChatAssetCatalogService chatAssetCatalogService;

    public ChatAssetAccessService(ChatRecordStore chatRecordStore, ChatAssetCatalogService chatAssetCatalogService) {
        this.chatRecordStore = chatRecordStore;
        this.chatAssetCatalogService = chatAssetCatalogService;
    }

    public boolean canRead(String chatId, String normalizedFilePath) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(normalizedFilePath)) {
            return false;
        }

        ChatDetailResponse chatDetail;
        try {
            chatDetail = chatRecordStore.loadChat(chatId, false);
        } catch (Exception ex) {
            log.debug(
                    "Failed to load chat detail while checking asset access chatId={}, fallback=deny access",
                    chatId,
                    ex
            );
            return false;
        }

        Set<String> assets = resolveAllowedAssets(chatDetail);
        if (chatAssetCatalogService != null) {
            try {
                for (QueryRequest.Reference reference : chatAssetCatalogService.listAssets(chatId)) {
                    if (reference != null) {
                        addNormalized(assets, reference.url());
                    }
                }
            } catch (Exception ex) {
                log.debug(
                        "Failed to load live chat assets chatId={}, fallback=persisted history assets only",
                        chatId,
                        ex
                );
            }
        }
        return assets.contains(normalizedFilePath);
    }

    private Set<String> resolveAllowedAssets(ChatDetailResponse chatDetail) {
        Set<String> assets = new LinkedHashSet<>();
        if (chatDetail == null) {
            return assets;
        }

        List<QueryRequest.Reference> references = chatDetail.references();
        if (references != null) {
            for (QueryRequest.Reference reference : references) {
                if (reference == null) {
                    continue;
                }
                addNormalized(assets, reference.url());
            }
        }

        List<java.util.Map<String, Object>> events = chatDetail.events();
        if (events == null) {
            return assets;
        }
        for (java.util.Map<String, Object> event : events) {
            if (event == null) {
                continue;
            }
            Object typeValue = event.get("type");
            if (!"content.snapshot".equals(String.valueOf(typeValue))) {
                continue;
            }
            Object textValue = event.get("text");
            if (!(textValue instanceof String text) || !StringUtils.hasText(text)) {
                continue;
            }
            collectMarkdownLinks(assets, text);
        }

        return assets;
    }

    private void collectMarkdownLinks(Set<String> assets, String text) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(text);
        while (matcher.find()) {
            String rawTarget = matcher.group(1);
            if (!StringUtils.hasText(rawTarget)) {
                continue;
            }
            addNormalized(assets, normalizeMarkdownTarget(rawTarget));
        }
    }

    private String normalizeMarkdownTarget(String target) {
        String normalized = target.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return normalized.split("\\s+", 2)[0];
    }

    private void addNormalized(Set<String> assets, String rawPath) {
        String normalized = DataFilePathNormalizer.normalizeAssetReference(rawPath);
        if (StringUtils.hasText(normalized)) {
            assets.add(normalized);
        }
    }
}
