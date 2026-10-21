package dev.mikoto2000.rei.paper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** 論文の調査と整理に必要な論文キャッシュキーを扱う。 */
final class PaperCacheKey {
  /** 論文の調査と整理で受け渡す情報を、指定された内容から作成する。 */
  static String of(PaperRepository repository, Paper paper, Object... version) {
    var fields = new ArrayList<Object>(Arrays.asList(version));
    fields.add(repository.artifact(paper.id(), "source", "revision").orElse(""));
    fields.add(
        java.util.HexFormat.of().formatHex(hash(Objects.toString(paper.abstractText(), ""))));
    return PaperJson.write(fields);
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static byte[] hash(String text) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
