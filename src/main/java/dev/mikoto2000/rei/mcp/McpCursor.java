package dev.mikoto2000.rei.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** MCPによる機能の提供に必要なMCP続きの取得位置を扱う。 */
public final class McpCursor {
  /** 照合キー。 */
  private final byte[] key;

  /** 指定された値や利用先を使って、McpCursorを初期化する。 */
  public McpCursor(byte[] key) {
    if (key.length < 32) throw new IllegalArgumentException("Cursor key too short");
    this.key = key.clone();
  }

  /** MCPによる機能の提供で用いる署名を返す。 */
  private String sign(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  public String encode(String project, String run, String snapshot, int offset) {
    String body =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                (project + "\n" + run + "\n" + snapshot + "\n" + offset)
                    .getBytes(StandardCharsets.UTF_8));
    return body + "." + sign(body);
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  public int decode(String cursor, String project, String run, String snapshot) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (cursor.length() > 2000) throw new IllegalArgumentException();
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 2
          || !java.security.MessageDigest.isEqual(
              sign(parts[0]).getBytes(StandardCharsets.UTF_8),
              parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
      String[] fields =
          new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
              .split("\n", -1);
      if (fields.length != 4
          || !fields[0].equals(project)
          || !fields[1].equals(run)
          || !fields[2].equals(snapshot)) throw new IllegalArgumentException();
      int offset = Integer.parseInt(fields[3]);
      if (offset < 0) throw new IllegalArgumentException();
      return offset;
    } catch (IllegalArgumentException e) {
      throw new McpError("INVALID_ARGUMENT");
    }
  }
}
