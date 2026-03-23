package com.linlay.agentplatform.security;

import com.linlay.agentplatform.security.JwksJwtVerifier.JwtPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

@Component
public class ChatImageTokenHelper {

    private final ChatImageTokenService chatImageTokenService;

    public ChatImageTokenHelper(ChatImageTokenService chatImageTokenService) {
        this.chatImageTokenService = chatImageTokenService;
    }

    public JwtPrincipal resolvePrincipal(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        Object raw = exchange.getAttribute(ApiJwtAuthWebFilter.JWT_PRINCIPAL_ATTR);
        if (raw instanceof JwtPrincipal principal) {
            return principal;
        }
        return null;
    }

    public String issueChatImageToken(ServerWebExchange exchange, String chatId) {
        return issueChatImageToken(resolvePrincipal(exchange), chatId);
    }

    public String issueChatImageToken(JwtPrincipal principal, String chatId) {
        if (principal == null || !StringUtils.hasText(principal.subject()) || !StringUtils.hasText(chatId)) {
            return null;
        }
        return chatImageTokenService.issueToken(principal.subject(), chatId);
    }
}
