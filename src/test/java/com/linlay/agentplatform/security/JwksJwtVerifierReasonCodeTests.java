package com.linlay.agentplatform.security;

import com.linlay.agentplatform.config.properties.AppAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwksJwtVerifierReasonCodeTests {

    @Test
    void shouldReturnReasonForMissingToken() {
        JwksJwtVerifier verifier = new JwksJwtVerifier(new AppAuthProperties());

        JwksJwtVerifier.VerifyResult result = verifier.verifyDetailed(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("token_missing");
    }

    @Test
    void shouldReturnReasonForTokenParseFailure() {
        JwksJwtVerifier verifier = new JwksJwtVerifier(new AppAuthProperties());

        JwksJwtVerifier.VerifyResult result = verifier.verifyDetailed("not-a-jwt");

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("token_parse_failed");
    }

    @Test
    void shouldReturnReasonForClaimInvalid() throws Exception {
        JwksJwtVerifier verifier = new JwksJwtVerifier(new AppAuthProperties());
        String token = issueToken(null);

        JwksJwtVerifier.VerifyResult result = verifier.verifyDetailed(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("claim_invalid");
    }

    @Test
    void shouldReturnReasonForSignatureInvalid() throws Exception {
        JwksJwtVerifier verifier = new JwksJwtVerifier(new AppAuthProperties());
        String token = issueToken(Date.from(Instant.now().plusSeconds(120)));

        JwksJwtVerifier.VerifyResult result = verifier.verifyDetailed(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("signature_invalid");
    }

    private String issueToken(Date expirationTime) throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .issueTime(Date.from(Instant.now()));
        if (expirationTime != null) {
            claims.expirationTime(expirationTime);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims.build()
        );
        JWSSigner signer = new RSASSASigner(rsaKey.toPrivateKey());
        jwt.sign(signer);
        return jwt.serialize();
    }
}
