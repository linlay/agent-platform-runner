package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.auth")
public class AppAuthProperties {

    private boolean enabled = true;
    private String jwksUri;
    private String issuer;
    private Long jwksCacheSeconds;
    private String localPublicKey;

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

    public Long getJwksCacheSeconds() {
        return jwksCacheSeconds;
    }

    public void setJwksCacheSeconds(Long jwksCacheSeconds) {
        this.jwksCacheSeconds = jwksCacheSeconds;
    }

    public String getLocalPublicKey() {
        return localPublicKey;
    }

    public void setLocalPublicKey(String localPublicKey) {
        this.localPublicKey = localPublicKey;
    }
}
