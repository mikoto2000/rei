package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTPS音声素材通信経路の結果と状態の契約を検証するテスト。 */
class HttpsVoiceAssetTransportTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う内容。 */
  final byte[] content = "fixed asset".getBytes();

  /** {@code asset} の条件を再現するための補助情報を返す。 */
  VoiceModelManifest.Asset asset() throws Exception {
    return new VoiceModelManifest.Asset(
        "asset",
        URI.create("https://example.org/fixed"),
        content.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
        "MIT");
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void exactContentPassesSizeAndHashChecks() throws Exception {
    var counts = new ArrayList<Long>();
    HttpsVoiceAssetTransport.writeBody(
        asset(),
        new ByteArrayInputStream(content),
        root.resolve("asset"),
        new VoiceAssetTransport.Cancellation(),
        counts::add);
    assertThat(Files.readAllBytes(root.resolve("asset"))).isEqualTo(content);
    assertThat(counts.getLast()).isEqualTo((long) content.length);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesOversizeShortOrSameSizeCorruptBody() throws Exception {
    for (byte[] invalid :
        List.of(
            new byte[content.length + 1], new byte[content.length - 1], new byte[content.length])) {
      Path target = root.resolve(UUID.randomUUID().toString());
      assertThatThrownBy(
              () ->
                  HttpsVoiceAssetTransport.writeBody(
                      asset(),
                      new ByteArrayInputStream(invalid),
                      target,
                      new VoiceAssetTransport.Cancellation(),
                      n -> {}))
          .isInstanceOf(IOException.class);
      if (Files.exists(target))
        assertThat(Files.size(target)).isLessThanOrEqualTo((long) content.length);
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationClosesBlockedBodyAndPreventsReadyFile() throws Exception {
    var entered = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var token = new VoiceAssetTransport.Cancellation();
    var input =
        new InputStream() {
          /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
          public int read() {
            throw new UnsupportedOperationException();
          }

          /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
          public int read(byte[] b, int offset, int length) throws IOException {
            entered.countDown();
            try {
              closed.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new InterruptedIOException();
            }
            return -1;
          }

          /** 検証で利用した対象の終了を扱う。 */
          public void close() {
            closed.countDown();
          }
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future =
          executor.submit(
              () -> {
                try {
                  HttpsVoiceAssetTransport.writeBody(
                      asset(), input, root.resolve("asset"), token, n -> {});
                  return "unexpected";
                } catch (Exception e) {
                  return e.getClass().getSimpleName();
                }
              });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      token.cancel();
      assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("InterruptedIOException");
      assertThat(closed.getCount()).isZero();
    } finally {
      token.cancel();
    }
  }

  /** 音声による対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void redirectCannotDowngradeHttpsOrCarryCredentials() {
    for (String invalid :
        List.of(
            "http://example.org/file",
            "file:///tmp/model",
            "https://user:secret@example.org/file",
            "https://example.org/file#fragment"))
      assertThatThrownBy(() -> VoiceModelManifest.requireHttps(URI.create(invalid)))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
