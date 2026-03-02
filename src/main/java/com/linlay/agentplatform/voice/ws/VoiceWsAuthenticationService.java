package com.linlay.agentplatform.voice.ws;

import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.VoiceWsProperties;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.security.JwksJwtVerifier.JwtPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.WebSocketSession;

@Component
public class VoiceWsAuthenticationService {

    private static final String AUTH_PREFIX = "Bearer ";

    private final AppAuthProperties authProperties;
    private final VoiceWsProperties voiceWsProperties;
    private final JwksJwtVerifier jwksJwtVerifier;

    public VoiceWsAuthenticationService(
            AppAuthProperties authProperties,
            VoiceWsProperties voiceWsProperties,
            JwksJwtVerifier jwksJwtVerifier
    ) {
        this.authProperties = authProperties;
        this.voiceWsProperties = voiceWsProperties;
        this.jwksJwtVerifier = jwksJwtVerifier;
    }

    public AuthResult authenticate(WebSocketSession session) {
        if (!voiceWsProperties.isAuthRequired()) {
            return AuthResult.success(null);
        }
        if (!authProperties.isEnabled()) {
            return AuthResult.success(null);
        }

        String authorization = session.getHandshakeInfo().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(AUTH_PREFIX)) {
            return AuthResult.failed("UNAUTHORIZED", "missing or invalid Authorization header");
        }

        String token = authorization.substring(AUTH_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            return AuthResult.failed("UNAUTHORIZED", "missing bearer token");
        }

        JwksJwtVerifier.VerifyResult verifyResult = jwksJwtVerifier.verifyDetailed(token);
        if (!verifyResult.valid()) {
            return AuthResult.failed("UNAUTHORIZED", "jwt verification failed");
        }

        return AuthResult.success(verifyResult.principal());
    }

    public record AuthResult(
            boolean authenticated,
            JwtPrincipal principal,
            String code,
            String message
    ) {
        public static AuthResult success(JwtPrincipal principal) {
            return new AuthResult(true, principal, null, null);
        }

        public static AuthResult failed(String code, String message) {
            return new AuthResult(false, null, code, message);
        }
    }
}
