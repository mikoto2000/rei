package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** findFile の型・値・JSON構造の不備が補正対象になることを確認する。 */
class FindFileArgumentsTest {
  /** 任意件数を省略でき、従来上限を超える300件と新上限の500件を受け付ける。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"keywords\":[\".md\"]}",
        "{\"keywords\":[null,\" \",\".md\"],\"maxResults\":null}",
        "{\"keywords\":[\".md\"],\"maxResults\":1}",
        "{\"keywords\":[\".md\"],\"maxResults\":300}",
        "{\"keywords\":[\".md\"],\"maxResults\":500}"
      })
  void acceptsValidRequests(String request) {
    assertDoesNotThrow(
        () -> FindFileArguments.validate("findFile", "{\"request\":" + request + "}"));
  }

  /** 変換時例外になる前に、不正な構造や型も修正案内へ変換する。 */
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "{",
        "[]",
        "{}",
        "{\"request\":null}",
        "{\"request\":{\"keywords\":[]}}",
        "{\"request\":{\"keywords\":[null,\" \"]}}",
        "{\"request\":{\"keywords\":\".md\"}}",
        "{\"request\":{\"keywords\":[\".md\",1]}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":0}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":501}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":2147483648}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":1.5}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":\"300\"}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":true}}",
        "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":1,\"maxResults\":500}}",
        "{\"request\":{\"keywords\":[\".md\"]}} {}"
      })
  void rejectsInvalidArgumentsBeforeBinding(String input) {
    assertThrows(
        InvalidToolArgumentsException.class, () -> FindFileArguments.validate("findFile", input));
    assertDoesNotThrow(() -> FindFileArguments.validate("anotherTool", input));
  }
}
