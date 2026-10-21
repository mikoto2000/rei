package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** 上限内に収めた本文の結果と状態の契約を検証するテスト。 */
class BoundedBodyTest {
  /** 検証に使う活動中。 */
  private static final Runnable ACTIVE = () -> {};

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void exactIdentityLimitIsAcceptedAndOneMoreByteIsRejected() {
    assertArrayEquals(new byte[64], BoundedBody.decode(new byte[64], "", 64, 64, ACTIVE));
    assertEquals(
        HttpFetchException.Code.WIRE_LIMIT,
        assertThrows(
                HttpFetchException.class,
                () -> BoundedBody.decode(new byte[65], "", 64, 100, ACTIVE))
            .code());
    assertEquals(
        HttpFetchException.Code.DECODED_LIMIT,
        assertThrows(
                HttpFetchException.class,
                () -> BoundedBody.decode(new byte[64], "", 64, 63, ACTIVE))
            .code());
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void wireChunksCannotBypassLimit() {
    assertDoesNotThrow(() -> BoundedBody.checkWireSize(60, 4, 64));
    assertThrows(HttpFetchException.class, () -> BoundedBody.checkWireSize(60, 5, 64));
    assertThrows(
        HttpFetchException.class, () -> BoundedBody.checkWireSize(1, Integer.MAX_VALUE, 64));
  }

  /** {@code gzip} の条件を再現するための補助情報を返す。 */
  byte[] gzip(String value) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var compressed = new GZIPOutputStream(output)) {
      compressed.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return output.toByteArray();
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void gzipExpansionIsBounded() throws Exception {
    var body = gzip("x".repeat(4096));
    assertTrue(body.length < 128);
    assertEquals(4096, BoundedBody.decode(body, "gzip", 128, 4096, ACTIVE).length);
    assertEquals(
        HttpFetchException.Code.DECODED_LIMIT,
        assertThrows(
                HttpFetchException.class, () -> BoundedBody.decode(body, "gzip", 128, 64, ACTIVE))
            .code());
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void concatenatedGzipMembersShareTheDecodedLimit() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(gzip("a"));
    bytes.write(gzip("b"));
    assertEquals(
        "ab",
        new String(
            BoundedBody.decode(bytes.toByteArray(), "gzip", 100, 2, ACTIVE),
            StandardCharsets.UTF_8));
    assertThrows(
        HttpFetchException.class,
        () -> BoundedBody.decode(bytes.toByteArray(), "gzip", 100, 1, ACTIVE));
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void emptyMembersCheckCancellationWithoutRecursing() throws Exception {
    var bytes = new ByteArrayOutputStream();
    for (int i = 0; i < 2000; i++) bytes.write(gzip(""));
    var checks = new AtomicInteger();
    assertThrows(
        CancellationException.class,
        () ->
            BoundedBody.decode(
                bytes.toByteArray(),
                "gzip",
                100_000,
                64,
                () -> {
                  if (checks.incrementAndGet() >= 100) throw new CancellationException();
                }));
  }

  /** 外部情報の取得で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void deflateIsDecodedAndUnknownOrDamagedEncodingIsRejected() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var compressed = new DeflaterOutputStream(bytes)) {
      compressed.write("hello".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(
        "hello",
        new String(
            BoundedBody.decode(bytes.toByteArray(), "deflate", 64, 64, ACTIVE),
            StandardCharsets.UTF_8));
    assertThrows(
        HttpFetchException.class, () -> BoundedBody.decode(new byte[0], "br", 64, 64, ACTIVE));
    assertThrows(
        HttpFetchException.class,
        () -> BoundedBody.decode(new byte[] {1, 2}, "gzip", 64, 64, ACTIVE));
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationIsNotTurnedIntoAFetchFailure() {
    assertThrows(
        CancellationException.class,
        () ->
            BoundedBody.decode(
                new byte[1],
                "",
                64,
                64,
                () -> {
                  throw new CancellationException();
                }));
  }
}
