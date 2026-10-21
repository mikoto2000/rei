package dev.mikoto2000.rei.core.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 完了Engineの結果と状態の契約を検証するテスト。 */
class CompletionEngineTest {
  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  static CompletionContext context(String token, String type, Path directory) {
    return new CompletionContext(
        token,
        token.length(),
        List.of(token),
        0,
        0,
        token,
        List.of(),
        Set.of(type),
        List.of(),
        directory);
  }

  /** 検証で使用する提供元を、指定された条件に合わせて用意する。 */
  static CompletionProvider provider(int priority, boolean supports, String... values) {
    return new CompletionProvider() {
      /** {@code priority} の条件を再現するための補助情報を返す。 */
      public int priority() {
        return priority;
      }

      /** 検証用に、入力候補の提示において、への対応可否を返す。 */
      public boolean supports(CompletionContext context) {
        return supports;
      }

      /** {@code complete} の条件を再現するための補助情報を返す。 */
      public List<CompletionCandidate> complete(CompletionContext context) {
        return java.util.Arrays.stream(values)
            .map(v -> CompletionCandidate.value(v, "test"))
            .toList();
      }
    };
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void selectsHighestPriorityAndMergesTiesInRegistrationOrder() {
    var engine =
        new CompletionEngine()
            .register(provider(0, true, "fallback"))
            .register(provider(10, true, "alpha", "shared"))
            .register(provider(10, true, "shared", "beta"))
            .register(provider(20, false, "unsupported"));
    assertThat(engine.complete(context("", "custom", Path.of("."))))
        .extracting(CompletionCandidate::value)
        .containsExactly("alpha", "shared", "beta");
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptySpecificProviderSuppressesFallback() {
    assertThat(
            new CompletionEngine()
                .register(provider(10, true))
                .register(provider(0, true, "wrong"))
                .complete(context("", "custom", Path.of("."))))
        .isEmpty();
  }

  /** 入力候補の提示で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void isolatesFailuresDuringSupportsAndCompletion() {
    var engine =
        new CompletionEngine()
            .register(
                new CompletionProvider() {
                  /** 検証用に、入力候補の提示において、への対応可否を返す。 */
                  public boolean supports(CompletionContext c) {
                    throw new IllegalStateException();
                  }

                  /** {@code complete} の条件を再現するための補助情報を返す。 */
                  public List<CompletionCandidate> complete(CompletionContext c) {
                    throw new AssertionError();
                  }
                })
            .register(
                new CompletionProvider() {
                  /** 検証用に、入力候補の提示において、への対応可否を返す。 */
                  public boolean supports(CompletionContext c) {
                    return true;
                  }

                  /** {@code complete} の条件を再現するための補助情報を返す。 */
                  public List<CompletionCandidate> complete(CompletionContext c) {
                    throw new IllegalStateException();
                  }
                })
            .register(provider(100, true, "ok"));
    assertThat(engine.complete(context("", "custom", Path.of("."))))
        .extracting(CompletionCandidate::value)
        .containsExactly("ok");
  }
}
