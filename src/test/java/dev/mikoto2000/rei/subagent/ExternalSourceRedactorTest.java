package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 外部取得元Redactorの結果と状態の契約を検証するテスト。 */
class ExternalSourceRedactorTest {
  /** {@code safeAndIdempotent} の条件を再現するため、検証用の操作や状態を扱う。 */
  private void safeAndIdempotent(String input, String expected) {
    var result = ExternalSourceRedactor.redact(input);
    assertEquals(expected, result.content());
    assertTrue(result.redacted());
    assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(result.content()));
    assertEquals(result.content(), ExternalSourceRedactor.redact(result.content()).content());
    assertTrue(ExternalSourceRedactor.redact(result.content()).redacted());
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesJsonPunctuationEscapesAndNeighboringValues() throws Exception {
    String input =
        "{\"password\":\"s\\\"ynthetic\",\"api_key\":\"demo\",\"authorization\":\"Bearer fake\",\"token\":123,\"user\":\"demo\",\"enabled\":true}";
    String expected =
        "{\"password\":\"[REDACTED]\",\"api_key\":\"[REDACTED]\",\"authorization\":\"Bearer [REDACTED]\",\"token\":\"[REDACTED]\",\"user\":\"demo\",\"enabled\":true}";
    safeAndIdempotent(input, expected);
    var json =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(ExternalSourceRedactor.redact(input).content());
    assertEquals("demo", json.path("user").asString());
    assertTrue(json.path("enabled").asBoolean());
  }

  /** 補助エージェントへの委譲で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void masksEscapedQuotedAssignmentsInsideJsonStringExamples() {
    String input = "{\"snippet\":\"password=\\\"synthetic\\\"; username=demo\"}";
    String expected = "{\"snippet\":\"password=\\\"[REDACTED]\\\"; username=demo\"}";
    safeAndIdempotent(input, expected);
    var json =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(ExternalSourceRedactor.redact(input).content());
    assertEquals("password=\"[REDACTED]\"; username=demo", json.path("snippet").asString());
    assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(json.path("snippet").asString()));
  }

  /** 引用形式一覧を保持することを確認する。 */
  @Test
  void preservesQuoteStylesAndCrlfInYamlPropertiesAndCodeExamples() {
    String input =
        "password: 'demo value'\r\n\"api-key\": \"demo-key\"\r\nspring.datasource.password=demo#suffix!\r\nString password = \"demo\"; int count = 1;\r\n'authorization': 'Basic demo'\r\n";
    String expected =
        "password: '[REDACTED]'\r\n\"api-key\": \"[REDACTED]\"\r\nspring.datasource.password=[REDACTED]\r\nString password = \"[REDACTED]\"; int count = 1;\r\n'authorization': 'Basic [REDACTED]'\r\n";
    safeAndIdempotent(input, expected);
    // A quote-preserving snapshot must not use the event redactor's quote normalization as a
    // rejection.
    assertNotEquals(expected, dev.mikoto2000.rei.event.CredentialRedactor.redact(expected));
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void doesNotConsumeTheNextLineOrOtherAssignments() {
    String input =
        "password:\nusername: demo\nString password=demo; int count=1;\ntoken = demo, username = other\n";
    String expected =
        "password:\nusername: demo\nString password=[REDACTED]; int count=1;\ntoken = [REDACTED], username = other\n";
    safeAndIdempotent(input, expected);
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void masksAllRecognizedAssignmentKeysIncludingQualifiedAndCamelCaseNames() {
    var keys =
        List.of(
            "password",
            "api_key",
            "api-key",
            "apiKey",
            "access_token",
            "refreshToken",
            "id_token_hint",
            "code_verifier",
            "token",
            "secret",
            "client_secret",
            "credential",
            "authorization",
            "accessJwt",
            "refreshJwt",
            "privateKey",
            "DB_PASSWORD",
            "myPassword",
            "spring.datasource.password");
    for (String key : keys) {
      String input = "\"" + key + "\":\"demo\"";
      assertTrue(ExternalSourceRedactor.hasUnmaskedCredentials(input), key);
      safeAndIdempotent(input, "\"" + key + "\":\"[REDACTED]\"");
    }
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void decodesUnicodeEscapedSensitiveKeysWithoutRewritingTheKeysOrTheirJsonSyntax() {
    safeAndIdempotent(
        "{\"pass\\u0077ord\":\"PRIVATE\",\"user\":\"demo\"}",
        "{\"pass\\u0077ord\":\"[REDACTED]\",\"user\":\"demo\"}");
    safeAndIdempotent(
        "```json\n{\"pass\\u0077ord\":\"PRIVATE\",\"api\\u005fkey\":\"OTHER\"}\n```",
        "```json\n{\"pass\\u0077ord\":\"[REDACTED]\",\"api\\u005fkey\":\"[REDACTED]\"}\n```");
    safeAndIdempotent(
        "{\"\\u0070\\u0061\\u0073\\u0073\\u0077\\u006f\\u0072\\u0064\":\"PRIVATE\"}",
        "{\"\\u0070\\u0061\\u0073\\u0073\\u0077\\u006f\\u0072\\u0064\":\"[REDACTED]\"}");
    String ordinary = "{\"na\\u006de\":\"demo\"}";
    assertEquals(ordinary, ExternalSourceRedactor.redact(ordinary).content());
    assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(ordinary));
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void masksStandaloneJwtApiAndSlackTokensAndAuthorizationSchemes() {
    String jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJkZW1vIn0.c3ludGhldGlj";
    String input =
        "JWT: "
            + jwt
            + "\nkey: sk-abcdefghijklmnop1234\nSlack xoxb-demo-token and xapp-demo-token\nAuthorization: Bearer demo-token\nHeader Basic ZGVtbzpwYXNzd29yZA==\n";
    String expected =
        "JWT: [REDACTED]\nkey: [REDACTED]\nSlack [REDACTED] and [REDACTED]\nAuthorization: Bearer [REDACTED]\nHeader Basic [REDACTED]\n";
    safeAndIdempotent(input, expected);
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void masksPrivateKeysWhileKeepingOriginalLineEndingsAndSurroundingText() {
    String input =
        "before\r\n-----BEGIN RSA PRIVATE KEY-----\r\nSYNTHETIC-ONLY\r\n-----END RSA PRIVATE KEY-----\r\nafter\r\n";
    safeAndIdempotent(input, "before\r\n[REDACTED]\r\n\r\n\r\nafter\r\n");
    var incomplete =
        ExternalSourceRedactor.redact("before\n-----BEGIN PRIVATE KEY-----\nSYNTHETIC-ONLY");
    assertEquals("before\n[REDACTED]\n", incomplete.content());
    assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(incomplete.content()));
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesYamlBlockSyntaxIndentationNewlinesAndTheNextProperty() {
    String input =
        "password: |\r\n  first-demo\r\n  second-demo\r\nusername: demo\r\n  token: >-\r\n    first-token\r\n    second-token\r\n  mode: example\r\n";
    String expected =
        "password: |\r\n  [REDACTED]\r\n  \r\nusername: demo\r\n  token: >-\r\n    [REDACTED]\r\n    \r\n  mode: example\r\n";
    safeAndIdempotent(input, expected);
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesMultilineQuotedValueLineBreaksAndMasksPlaceholderExpressions() {
    safeAndIdempotent(
        "password: \"first\r\nsecond\"\r\nusername: demo",
        "password: \"[REDACTED]\r\n\"\r\nusername: demo");
    safeAndIdempotent(
        "spring.datasource.password=${DB_PASSWORD:demo}\nusername=demo",
        "spring.datasource.password=[REDACTED]\nusername=demo");
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void existingMarkersAndOrdinaryDocumentsAreNotRemasked() {
    for (String safe :
        List.of(
            "password=\"[REDACTED]\"",
            "'password': '[REDACTED]'",
            "\"password\":\"[REDACTED]\"",
            "Authorization: Bearer [REDACTED]",
            "password: |\n  [REDACTED]\n")) {
      assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(safe));
      assertEquals(safe, ExternalSourceRedactor.redact(safe).content());
      assertTrue(ExternalSourceRedactor.redact(safe).redacted());
    }
    String ordinary = "Java classes use methods.\r\nusername: demo\r\n";
    assertEquals(ordinary, ExternalSourceRedactor.redact(ordinary).content());
    assertFalse(ExternalSourceRedactor.redact(ordinary).redacted());
    assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(ordinary));
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unsupportedStructuredOrIncompletePlaceholderValuesRemainRestricted() {
    for (String unsafe :
        List.of(
            "{\"password\":{\"value\":\"synthetic\"}}", "password=[one,two]", "password=${DEMO"))
      assertTrue(
          ExternalSourceRedactor.hasUnmaskedCredentials(
              ExternalSourceRedactor.redact(unsafe).content()));
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void opaqueBareValuesIncludeAngleHashAndUrlPunctuationWithoutLeavingSecretSuffixes() {
    for (String secret :
        List.of(
            "<PRIVATE>",
            "abc#PRIVATE",
            "https://example.test/path?private=demo&next=value#PRIVATE",
            "abc:def@host/path+suffix="))
      safeAndIdempotent(
          "password=" + secret + "\r\nusername=demo", "password=[REDACTED]\r\nusername=demo");
    safeAndIdempotent("{password:<PRIVATE>,username:demo}", "{password:[REDACTED],username:demo}");
    safeAndIdempotent(
        "spring.security.user.password={noop}PRIVATE\nusername=demo",
        "spring.security.user.password={noop}[REDACTED]\nusername=demo");
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesAuthorizationSchemesAndQuotedHeaderOrJsonBoundaries() {
    safeAndIdempotent(
        "Authorization: Bearer <PRIVATE>\r\n", "Authorization: Bearer [REDACTED]\r\n");
    safeAndIdempotent("Authorization: Basic <PRIVATE>\n", "Authorization: Basic [REDACTED]\n");
    safeAndIdempotent(
        "curl -H \"Authorization: Bearer <PRIVATE>\"",
        "curl -H \"Authorization: Bearer [REDACTED]\"");
    safeAndIdempotent(
        "{\"authorization\":\"Bearer <PRIVATE>\",\"user\":\"demo\"}",
        "{\"authorization\":\"Bearer [REDACTED]\",\"user\":\"demo\"}");
    safeAndIdempotent("Header Bearer <PRIVATE>", "Header Bearer [REDACTED]");
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void ambiguousWhitespaceOrInternalSeparatorsFailClosedInsteadOfPartlyMasking() {
    for (String unsafe :
        List.of(
            "password=first second",
            "password=demo;username=PRIVATE",
            "password=demo,username=PRIVATE",
            "password=[REDACTED] PRIVATE",
            "password=[REDACTED]<PRIVATE>",
            "password=[REDACTED]#PRIVATE",
            "Authorization: Bearer [REDACTED]<PRIVATE>",
            "Header Bearer <demo>#PRIVATE"))
      assertTrue(
          ExternalSourceRedactor.hasUnmaskedCredentials(
              ExternalSourceRedactor.redact(unsafe).content()));
    safeAndIdempotent(
        "password=demo # a comment\nusername=demo",
        "password=[REDACTED] # a comment\nusername=demo");
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void markdownListValuesWithMultipleCasesAreFullyMaskedWithoutLeavingTheirRemainder() {
    String input =
        "- password: 0 / 1 / 255 / 256 characters, normal / missing\r\n"
            + "- email / password: null demonstration\r\n"
            + "  * token: first second PRIVATE\r\n- username: demo\r\n";
    String expected =
        "- password: [REDACTED]\r\n- email / password: [REDACTED]\r\n"
            + "  * token: [REDACTED]\r\n- username: demo\r\n";
    safeAndIdempotent(input, expected);
    safeAndIdempotent("- password: [REDACTED] PRIVATE", "- password: [REDACTED]");
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void boundedLongInputWithManyNearMatchesCompletesWithoutChangingOrdinaryText() {
    String content = "ordinary_api_key_name_that_is_not_an_assignment ".repeat(5000);
    assertTrue(content.length() <= 262144);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          assertEquals(content, ExternalSourceRedactor.redact(content).content());
          assertFalse(ExternalSourceRedactor.hasUnmaskedCredentials(content));
        });
  }
}
