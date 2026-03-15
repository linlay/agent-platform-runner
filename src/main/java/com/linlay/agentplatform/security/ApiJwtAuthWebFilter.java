package com.linlay.agentplatform.security;

import java.nio.charset.StandardCharsets;

import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.service.LoggingSanitizer;
import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.ChatImageTokenProperties;
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

    private final AppAuthProperties authProperties;
    private final ChatImageTokenProperties chatImageTokenProperties;
    private final LoggingAgentProperties loggingAgentProperties;
    private final JwksJwtVerifier jwtVerifier;

    public ApiJwtAuthWebFilter(
            AppAuthProperties authProperties,
            ChatImageTokenProperties chatImageTokenProperties,
            LoggingAgentProperties loggingAgentProperties,
            JwksJwtVerifier jwtVerifier
    ) {
        this.authProperties = authProperties;
        this.chatImageTokenProperties = chatImageTokenProperties;
        this.loggingAgentProperties = loggingAgentProperties;
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!authProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!StringUtils.hasText(path) || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        if (isDataApiRequestAllowedWithoutBearer(exchange)) {
            return chain.filter(exchange);
        }

        BearerTokenResolution tokenResolution = resolveBearerToken(exchange);
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

    private boolean isDataApiRequestAllowedWithoutBearer(ServerWebExchange exchange) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        if (!"/api/data".equals(path)) {
            return false;
        }
        if (!chatImageTokenProperties.isDataTokenValidationEnabled()) {
            return true;
        }
        return exchange.getRequest().getQueryParams().containsKey("t");
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
