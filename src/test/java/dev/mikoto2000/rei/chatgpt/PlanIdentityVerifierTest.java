package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class PlanIdentityVerifierTest {
  @Test
  void signatureIssuerAudienceExpiryAndNonceAreVerified() throws Exception {
    var key = new RSAKeyGenerator(2048).generate();
    var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    var verifier = new PlanOAuth.OpenAiIdentityVerifier(decoder);
    String good =
        sign(key, "https://auth.openai.com", "client", "nonce", Instant.now().plusSeconds(600));
    assertEquals("sub", verifier.verify(good, "client", "nonce").subject());
    for (String bad :
        List.of(
            sign(
                key,
                "https://auth.openai.com",
                "client",
                "nonce",
                Instant.now().plusSeconds(600),
                Instant.now().plusSeconds(120)),
            sign(key, "wrong", "client", "nonce", Instant.now().plusSeconds(600)),
            sign(key, "https://auth.openai.com", "wrong", "nonce", Instant.now().plusSeconds(600)),
            sign(key, "https://auth.openai.com", "client", "wrong", Instant.now().plusSeconds(600)),
            sign(
                key, "https://auth.openai.com", "client", "nonce", Instant.now().minusSeconds(120)),
            sign(
                new RSAKeyGenerator(2048).generate(),
                "https://auth.openai.com",
                "client",
                "nonce",
                Instant.now().plusSeconds(600)))) {
      var error =
          assertThrows(ChatGptException.class, () -> verifier.verify(bad, "client", "nonce"));
      assertEquals(ChatGptException.Code.AUTH_INVALID, error.code());
      assertFalse(error.toString().contains(bad));
    }
  }

  private String sign(
      com.nimbusds.jose.jwk.RSAKey key,
      String issuer,
      String audience,
      String nonce,
      Instant expiry)
      throws Exception {
    return sign(key, issuer, audience, nonce, expiry, Instant.now().minusSeconds(10));
  }

  private String sign(
      com.nimbusds.jose.jwk.RSAKey key,
      String issuer,
      String audience,
      String nonce,
      Instant expiry,
      Instant issued)
      throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.RS256),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("sub")
                .audience(audience)
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(expiry))
                .claim("nonce", nonce)
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
