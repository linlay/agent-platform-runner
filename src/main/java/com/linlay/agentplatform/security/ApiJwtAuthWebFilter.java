package com.linlay.agentplatform.security;

import java.nio.charset.StandardCharsets;

import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.service.LoggingSanitizer;
import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.ChatImageTokenProperties;
import com.linlay.agentplatform.config.VoiceWsProperties;
import com.linlay.agentplatform.security.JwksJwtVerifier.JwtPrincipal;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class ApiJwtAuthWebFilter implements WebFilter {

    public static final String JWT_PRINCIPAL_ATTR = "APP_JWT_PRINCIPAL";

    private static final String AUTH_PREFIX = "Bearer ";
    private static final String VOICE_WS_QUERY_TOKEN_PARAM = "access_token";

    private final AppAuthProperties authProperties;
    private final ChatImageTokenProperties chatImageTokenProperties;
    private final VoiceWsProperties voiceWsProperties;
    private final LoggingAgentProperties loggingAgentProperties;
    private final JwksJwtVerifier jwtVerifier;

    public ApiJwtAuthWebFilter(
            AppAuthProperties authProperties,
            ChatImageTokenProperties chatImageTokenProperties,
            VoiceWsProperties voiceWsProperties,
            LoggingAgentProperties loggingAgentProperties,
            JwksJwtVerifier jwtVerifier
    ) {
        this.authProperties = authProperties;
        this.chatImageTokenProperties = chatImageTokenProperties;
        this.voiceWsProperties = voiceWsProperties;
        this.loggingAgentProperties = loggingAgentProperties;
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!authProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!StringUtils.hasText(path) || !path.startsWith("/api/ap/")) {
            return chain.filter(exchange);
        }

        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        if (!voiceWsProperties.isAuthRequired() && isVoiceWsPath(path)) {
            return chain.filter(exchange);
        }
        if (isDataApiTokenRequest(exchange)) {
            return chain.filter(exchange);
        }

        BearerTokenResolution tokenResolution;
        if (isVoiceWsPath(path) && voiceWsProperties.isAuthRequired()) {
            tokenResolution = resolveVoiceWsQueryToken(exchange);
        } else {
            tokenResolution = resolveBearerToken(exchange);
        }
        if (!StringUtils.hasText(tokenResolution.token())) {
            attachAuthRejectReason(exchange, tokenResolution.reasonCode());
            return writeUnauthorized(exchange);
        }
        JwksJwtVerifier.VerifyResult verifyResult = jwtVerifier.verifyDetailed(tokenResolution.token());
        JwtPrincipal principal = verifyResult.principal();
        if (principal == null) {
            attachAuthRejectReason(exchange, mapVerifyFailure(verifyResult.reasonCode()));
            return writeUnauthorized(exchange);
        }

        exchange.getAttributes().put(JWT_PRINCIPAL_ATTR, principal);
        return chain.filter(exchange);
    }

    private BearerTokenResolution resolveBearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(authorization)) {
            return new BearerTokenResolution(null, "missing_auth_header");
        }
        if (!authorization.startsWith(AUTH_PREFIX)) {
            return new BearerTokenResolution(null, "bad_bearer_format");
        }
        String token = authorization.substring(AUTH_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            return new BearerTokenResolution(null, "empty_bearer_token");
        }
        return new BearerTokenResolution(token, "ok");
    }

    private BearerTokenResolution resolveVoiceWsQueryToken(ServerWebExchange exchange) {
        if (!exchange.getRequest().getQueryParams().containsKey(VOICE_WS_QUERY_TOKEN_PARAM)) {
            return new BearerTokenResolution(null, "missing_ws_query_token");
        }
        String token = exchange.getRequest().getQueryParams().getFirst(VOICE_WS_QUERY_TOKEN_PARAM);
        if (!StringUtils.hasText(token)) {
            return new BearerTokenResolution(null, "empty_ws_query_token");
        }
        return new BearerTokenResolution(token.trim(), "ok");
    }

    private boolean isDataApiTokenRequest(ServerWebExchange exchange) {
        if (!chatImageTokenProperties.isDataTokenValidationEnabled()) {
            return false;
        }
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        if (!"/api/ap/data".equals(path)) {
            return false;
        }
        return exchange.getRequest().getQueryParams().containsKey("t");
    }

    private boolean isVoiceWsPath(String path) {
        return StringUtils.hasText(path) && voiceWsProperties.normalizedPath().equals(path.trim());
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private String mapVerifyFailure(String rawReason) {
        if ("claim_invalid".equals(rawReason)) {
            return "claim_invalid";
        }
        return "jwt_verify_failed";
    }

    private void attachAuthRejectReason(ServerWebExchange exchange, String reasonCode) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getAuth().isEnabled()) {
            return;
        }
        exchange.getAttributes().put(
                com.linlay.agentplatform.config.ApiRequestLoggingWebFilter.ATTR_AUTH_REJECT_REASON,
                LoggingSanitizer.sanitizeText(reasonCode)
        );
    }

    private record BearerTokenResolution(String token, String reasonCode) {
    }
}
