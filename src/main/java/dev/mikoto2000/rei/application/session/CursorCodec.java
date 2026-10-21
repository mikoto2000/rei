package dev.mikoto2000.rei.application.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;

/**
 * 会話の継続管理に必要な続きの取得位置Codecを扱う。
 *
 * <p>Versioned opaque transport token, bound to the requested resource/filter, never a file path.
 */
public final class CursorCodec {
  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  public String encode(String scope, CursorKey key) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeByte(1);
      out.writeUTF(scope);
      out.writeLong(key.time().getEpochSecond());
      out.writeInt(key.time().getNano());
      out.writeUTF(key.id());
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid cursor", error);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  public CursorKey decode(String scope, String token) {
    if (token == null) return null;
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (token.isEmpty() || token.length() > 4096) throw new IllegalArgumentException();
      var bytes = Base64.getUrlDecoder().decode(token);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token))
        throw new IllegalArgumentException();
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readUnsignedByte() != 1 || !in.readUTF().equals(scope))
        throw new IllegalArgumentException();
      long seconds = in.readLong();
      int nanos = in.readInt();
      if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException();
      var key = new CursorKey(Instant.ofEpochSecond(seconds, nanos), in.readUTF());
      if (key.id().isEmpty() || in.available() != 0) throw new IllegalArgumentException();
      return key;
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("Invalid cursor", error);
    }
  }
}
