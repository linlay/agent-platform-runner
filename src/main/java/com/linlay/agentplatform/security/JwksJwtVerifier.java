package com.linlay.agentplatform.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.linlay.agentplatform.config.properties.AppAuthProperties;
import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JwksJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwksJwtVerifier.class);

    private final AppAuthProperties authProperties;
    private final Object lock = new Object();

    private volatile CachedJwkSet cachedJwkSet;
    private volatile RSAKey localRsaKey;

    public JwksJwtVerifier(AppAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostConstruct
    void initialize() {
        if (!authProperties.isEnabled()) {
            localRsaKey = null;
            cachedJwkSet = null;
            return;
        }
        validateJwksConfiguration();
        validateLocalKeyConfiguration();
        localRsaKey = resolveLocalRsaKey();
    }

    public Optional<JwtPrincipal> verify(String token) {
        VerifyResult result = verifyDetailed(token);
        return Optional.ofNullable(result.principal());
    }

    public VerifyResult verifyDetailed(String token) {
        if (!StringUtils.hasText(token)) {
            return VerifyResult.failed("token_missing");
        }

        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token.trim());
        } catch (Exception ex) {
            return VerifyResult.failed("token_parse_failed");
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (Exception ex) {
            return VerifyResult.failed("claims_parse_failed");
        }

        if (!validateClaims(claims)) {
            return VerifyResult.failed("claim_invalid");
        }

        if (verifyWithLocalKey(jwt) || verifyWithJwksKeys(jwt)) {
            return VerifyResult.success(buildPrincipal(claims));
        }

        return VerifyResult.failed("signature_invalid");
    }

    private boolean validateClaims(JWTClaimsSet claims) {
        if (claims == null) {
            return false;
        }

        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
            return false;
        }

        if (StringUtils.hasText(authProperties.getIssuer())) {
            String issuer = claims.getIssuer();
            if (!StringUtils.hasText(issuer) || !authProperties.getIssuer().equals(issuer)) {
                return false;
            }
        }

        return StringUtils.hasText(claims.getSubject());
    }

    private List<JWK> resolveKeys(String kid) {
        JWKSet jwkSet = getCachedOrReloadedJwkSet();
        if (jwkSet == null || jwkSet.getKeys() == null || jwkSet.getKeys().isEmpty()) {
            return List.of();
        }

        if (!StringUtils.hasText(kid)) {
            return jwkSet.getKeys();
        }

        List<JWK> filtered = jwkSet.getKeys().stream()
            .filter(key -> kid.equals(key.getKeyID()))
            .toList();

        return filtered.isEmpty() ? jwkSet.getKeys() : filtered;
    }

    private JWKSet getCachedOrReloadedJwkSet() {
        if (!isJwksConfigured()) {
            return null;
        }

        Instant now = Instant.now();
        CachedJwkSet local = cachedJwkSet;
        if (local != null && now.isBefore(local.expireAt())) {
            return local.jwkSet();
        }

        synchronized (lock) {
            CachedJwkSet latest = cachedJwkSet;
            if (latest != null && now.isBefore(latest.expireAt())) {
                return latest.jwkSet();
            }

            try {
                String uri = authProperties.getJwksUri();
                JWKSet reloaded = JWKSet.load(URI.create(uri.trim()).toURL());
                long ttlSeconds = Math.max(30L, authProperties.getJwksCacheSeconds());
                cachedJwkSet = new CachedJwkSet(reloaded, Instant.now().plusSeconds(ttlSeconds));
                return reloaded;
            } catch (Exception ex) {
                if (latest != null) {
                    return latest.jwkSet();
                }
                log.warn("Failed to load JWKS from {}", authProperties.getJwksUri(), ex);
                return null;
            }
        }
    }

    private RSAKey resolveLocalRsaKey() {
        if (!isLocalKeyConfigured()) {
            return null;
        }

        String sourceProperty = "agent.auth.local-public-key-file";
        String pem = resolveLocalPublicKeyContent();
        try {
            JWK jwk = JWK.parseFromPEMEncodedObjects(pem.trim());
            if (!(jwk instanceof RSAKey rsaKey)) {
                throw new IllegalStateException(sourceProperty + " must be an RSA public key");
            }
            return rsaKey;
        } catch (NoClassDefFoundError | ExceptionInInitializerError ex) {
            return parseRsaPublicKeyFallback(pem.trim(), sourceProperty);
        } catch (IllegalStateException ex) {
            log.error("Invalid local public key configuration", ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Invalid local public key configuration", ex);
            throw new IllegalStateException(sourceProperty + " is not a valid PEM RSA public key", ex);
        }
    }

    private RSAKey parseRsaPublicKeyFallback(String pem, String sourceProperty) {
        try {
            String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
            if (!StringUtils.hasText(normalized)) {
                throw new IllegalStateException(sourceProperty + " is not a valid PEM RSA public key");
            }
            byte[] der = Base64.getDecoder().decode(normalized);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(der);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(keySpec);
            return new RSAKey.Builder(publicKey).build();
        } catch (IllegalStateException ex) {
            log.error("Invalid local public key configuration", ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Invalid local public key configuration", ex);
            throw new IllegalStateException(sourceProperty + " is not a valid PEM RSA public key", ex);
        }
    }

    private boolean verifyWithLocalKey(SignedJWT jwt) {
        RSAKey rsaKey = localRsaKey;
        return rsaKey != null && verifySignature(jwt, rsaKey);
    }

    private boolean verifyWithJwksKeys(SignedJWT jwt) {
        if (!isJwksConfigured()) {
            return false;
        }

        List<JWK> keys = resolveKeys(jwt.getHeader().getKeyID());
        if (keys.isEmpty()) {
            return false;
        }

        for (JWK key : keys) {
            if (!(key instanceof RSAKey rsaKey)) {
                continue;
            }
            if (verifySignature(jwt, rsaKey)) {
                return true;
            }
        }
        return false;
    }

    private JwtPrincipal buildPrincipal(JWTClaimsSet claims) {
        String subject = claims.getSubject();
        String scope = toStringClaim(claims, "scope");
        String deviceId = toStringClaim(claims, "device_id");
        Instant issuedAt = claims.getIssueTime() == null ? Instant.now() : claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();
        return new JwtPrincipal(subject, deviceId, scope, issuedAt, expiresAt);
    }

    private boolean verifySignature(SignedJWT jwt, RSAKey rsaKey) {
        try {
            return jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()));
        } catch (JOSEException ex) {
            return false;
        }
    }

    private String toStringClaim(JWTClaimsSet claims, String key) {
        Object raw = claims.getClaim(key);
        return raw == null ? null : String.valueOf(raw);
    }

    private void validateJwksConfiguration() {
        boolean hasUri = StringUtils.hasText(authProperties.getJwksUri());
        boolean hasIssuer = StringUtils.hasText(authProperties.getIssuer());
        boolean hasCache = authProperties.getJwksCacheSeconds() != null;
        boolean hasAny = hasUri || hasIssuer || hasCache;
        boolean hasAll = hasUri && hasIssuer && hasCache;

        if (!hasAny) {
            return;
        }
        if (!hasAll) {
            throw new IllegalStateException(
                "agent.auth.jwks-uri, agent.auth.issuer and agent.auth.jwks-cache-seconds must be configured together"
            );
        }
        if (authProperties.getJwksCacheSeconds() <= 0) {
            throw new IllegalStateException("agent.auth.jwks-cache-seconds must be greater than 0");
        }
    }

    private void validateLocalKeyConfiguration() {
        String localPublicKeyFile = authProperties.getLocalPublicKeyFile();
        if (localPublicKeyFile != null && !StringUtils.hasText(localPublicKeyFile)) {
            throw new IllegalStateException("agent.auth.local-public-key-file cannot be blank");
        }
    }

    private boolean isLocalKeyConfigured() {
        return StringUtils.hasText(authProperties.getLocalPublicKeyFile());
    }

    private String resolveLocalPublicKeyContent() {
        String rawPath = authProperties.getLocalPublicKeyFile();
        Path path = ConfigDirectorySupport.resolveConfigRelativePath(rawPath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("agent.auth.local-public-key-file does not exist: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("agent.auth.local-public-key-file is not readable: " + path);
        }
        try {
            String pem = Files.readString(path, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(pem)) {
                throw new IllegalStateException("agent.auth.local-public-key-file cannot be blank");
            }
            return pem;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read agent.auth.local-public-key-file: " + path, ex);
        }
    }

    private boolean isJwksConfigured() {
        return StringUtils.hasText(authProperties.getJwksUri())
            && StringUtils.hasText(authProperties.getIssuer())
            && authProperties.getJwksCacheSeconds() != null
            && authProperties.getJwksCacheSeconds() > 0;
    }

    private record CachedJwkSet(JWKSet jwkSet, Instant expireAt) {
    }

    public record JwtPrincipal(
            String subject,
            String deviceId,
            String scope,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    public record VerifyResult(
            JwtPrincipal principal,
            String reasonCode
    ) {
        public boolean valid() {
            return principal != null;
        }

        public static VerifyResult success(JwtPrincipal principal) {
            return new VerifyResult(principal, "ok");
        }

        public static VerifyResult failed(String reasonCode) {
            return new VerifyResult(null, reasonCode == null ? "unknown" : reasonCode);
        }
    }
}
