package com.linlay.agentplatform.voice.ws;

import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.VoiceWsProperties;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.security.JwksJwtVerifier.JwtPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class VoiceWsAuthenticationService {

    private static final String VOICE_WS_QUERY_TOKEN_PARAM = "access_token";

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

        String token = UriComponentsBuilder
                .fromUri(session.getHandshakeInfo().getUri())
                .build()
                .getQueryParams()
                .getFirst(VOICE_WS_QUERY_TOKEN_PARAM);
        if (!StringUtils.hasText(token)) {
            return AuthResult.failed("UNAUTHORIZED", "missing access_token query parameter");
        }

        JwksJwtVerifier.VerifyResult verifyResult = jwksJwtVerifier.verifyDetailed(token.trim());
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
