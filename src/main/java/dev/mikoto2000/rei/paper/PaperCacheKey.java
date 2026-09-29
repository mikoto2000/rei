package dev.mikoto2000.rei.paper;

import java.util.*;

final class PaperCacheKey {
  static String of(PaperRepository repository, Paper paper, Object... version) {
    var fields = new ArrayList<Object>(Arrays.asList(version));
    fields.add(repository.artifact(paper.id(), "source", "revision").orElse(""));
    fields.add(
        java.util.HexFormat.of().formatHex(hash(Objects.toString(paper.abstractText(), ""))));
    return PaperJson.write(fields);
  }

  private static byte[] hash(String text) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
