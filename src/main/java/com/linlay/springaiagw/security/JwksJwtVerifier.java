package com.linlay.springaiagw.security;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.linlay.springaiagw.config.AppAuthProperties;
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
        localRsaKey = resolveLocalRsaKey();
    }

    public Optional<JwtPrincipal> verify(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }

        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token.trim());
        } catch (Exception ex) {
            return Optional.empty();
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (Exception ex) {
            return Optional.empty();
        }

        if (!validateClaims(claims)) {
            return Optional.empty();
        }

        if (verifyWithLocalKey(jwt) || verifyWithJwksKeys(jwt)) {
            return Optional.of(buildPrincipal(claims));
        }

        return Optional.empty();
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
                if (!StringUtils.hasText(uri)) {
                    return latest == null ? null : latest.jwkSet();
                }

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
        if (!authProperties.isLocalPublicKeyEnabled()) {
            return null;
        }

        String pem = authProperties.getLocalPublicKey();
        if (!StringUtils.hasText(pem)) {
            throw new IllegalStateException(
                "agw.auth.local-public-key is required when agw.auth.local-public-key-enabled=true"
            );
        }

        try {
            JWK jwk = JWK.parseFromPEMEncodedObjects(pem.trim());
            if (!(jwk instanceof RSAKey rsaKey)) {
                throw new IllegalStateException("agw.auth.local-public-key must be an RSA public key");
            }
            return rsaKey;
        } catch (NoClassDefFoundError | ExceptionInInitializerError ex) {
            return parseRsaPublicKeyFallback(pem.trim());
        } catch (IllegalStateException ex) {
            log.error("Invalid local public key configuration", ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Invalid local public key configuration", ex);
            throw new IllegalStateException("agw.auth.local-public-key is not a valid PEM RSA public key", ex);
        }
    }

    private RSAKey parseRsaPublicKeyFallback(String pem) {
        try {
            String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
            if (!StringUtils.hasText(normalized)) {
                throw new IllegalStateException("agw.auth.local-public-key is not a valid PEM RSA public key");
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
            throw new IllegalStateException("agw.auth.local-public-key is not a valid PEM RSA public key", ex);
        }
    }

    private boolean verifyWithLocalKey(SignedJWT jwt) {
        if (!authProperties.isLocalPublicKeyEnabled()) {
            return false;
        }

        RSAKey rsaKey = localRsaKey;
        return rsaKey != null && verifySignature(jwt, rsaKey);
    }

    private boolean verifyWithJwksKeys(SignedJWT jwt) {
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
}
