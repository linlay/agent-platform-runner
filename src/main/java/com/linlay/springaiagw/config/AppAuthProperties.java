package com.linlay.springaiagw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agw.auth")
public class AppAuthProperties {

    private boolean enabled = true;
    private String jwksUri = "http://127.0.0.1:38080/api/auth/jwks";
    private String issuer = "http://127.0.0.1:38080";
    private long jwksCacheSeconds = 300;
    private boolean localPublicKeyEnabled = false;
    private String localPublicKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getJwksCacheSeconds() {
        return jwksCacheSeconds;
    }

    public void setJwksCacheSeconds(long jwksCacheSeconds) {
        this.jwksCacheSeconds = jwksCacheSeconds;
    }

    public boolean isLocalPublicKeyEnabled() {
        return localPublicKeyEnabled;
    }

    public void setLocalPublicKeyEnabled(boolean localPublicKeyEnabled) {
        this.localPublicKeyEnabled = localPublicKeyEnabled;
    }

    public String getLocalPublicKey() {
        return localPublicKey;
    }

    public void setLocalPublicKey(String localPublicKey) {
        this.localPublicKey = localPublicKey;
    }
}
