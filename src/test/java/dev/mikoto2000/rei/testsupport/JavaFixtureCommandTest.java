package dev.mikoto2000.rei.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java検証条件コマンドの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class JavaFixtureCommandTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 機能の提供で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesUnicodeWithWesternNativeEncoding() throws Exception {
    String previous = System.getProperty("native.encoding");
    try {
      System.setProperty("native.encoding", "windows-1252");
      launchesLongClasspathWithoutChangingLiteralArguments();
    } finally {
      if (previous == null) System.clearProperty("native.encoding");
      else System.setProperty("native.encoding", previous);
    }
  }

  /** 機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void launchesLongClasspathWithoutChangingLiteralArguments() throws Exception {
    assertArgumentRoundTrip("quotes \" with spaces and \\backslashes 日本語");
  }

  /** 機能の提供で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesCharactersOutsideTheOperatingSystemCodePage() throws Exception {
    assertArgumentRoundTrip("emoji 🧪 and 日本語, newline\n tab\t quote\" trailing\\");
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertArgumentRoundTrip(String argument) throws Exception {
    String classpath =
        String.join(java.io.File.pathSeparator, Collections.nCopies(1200, root.toString()))
            + java.io.File.pathSeparator
            + System.getProperty("java.class.path");
    assertThat(classpath.length()).isGreaterThan(32767);
    var command = JavaFixtureCommand.command(root, classpath, Echo.class, argument);
    assertThat(String.join(" ", command).length()).isLessThan(4096);
    var output = root.resolve("output.log");
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(Files.readString(output).trim())
          .isEqualTo(
              Base64.getEncoder()
                  .encodeToString(argument.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** 検証条件を再現するためのEcho。 */
  public static class Echo {
    /** 検証用に、機能の提供の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) {
      System.out.println(
          Base64.getEncoder()
              .encodeToString(args[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
  }
}
