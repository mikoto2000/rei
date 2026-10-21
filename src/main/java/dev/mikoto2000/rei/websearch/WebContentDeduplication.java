package dev.mikoto2000.rei.websearch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Web上の情報の調査に必要なWeb内容Deduplicationを扱う。 */
public final class WebContentDeduplication {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private WebContentDeduplication() {}

  /** Web上の情報の調査で用いる要求内容の照合値を返す。 */
  public static String fingerprint(String fullContent) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(fullContent.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Web上の情報の調査で用いるページ一覧を返す。 */
  public static List<WebSearchPage> pages(List<WebSearchPage> pages) {
    List<WebSearchPage> result = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    for (var page : pages) {
      Integer index = page.fingerprint() == null ? null : seen.get(page.fingerprint());
      if (index == null) {
        if (page.fingerprint() != null) seen.put(page.fingerprint(), result.size());
        result.add(page);
      } else {
        WebSearchMetrics.OBSERVED.add("duplicate_content", 1);
        result.set(index, result.get(index).withAliases(duplicateAliases(page.aliases())));
      }
    }
    return List.copyOf(result);
  }

  /** Web上の情報の調査で用いる項目一覧を返す。 */
  public static List<WebSearchAndReadItem> items(List<WebSearchAndReadItem> items) {
    List<WebSearchAndReadItem> result = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    for (var item : items) {
      Integer index = item.fingerprint() == null ? null : seen.get(item.fingerprint());
      if (index == null) {
        if (item.fingerprint() != null) seen.put(item.fingerprint(), result.size());
        result.add(item);
      } else {
        WebSearchMetrics.OBSERVED.add("duplicate_content", 1);
        result.set(index, result.get(index).withAliases(duplicateAliases(item.aliases())));
      }
    }
    return List.copyOf(result);
  }

  /** Web上の情報の調査で用いる重複の有無別名一覧を返す。 */
  private static List<WebSourceAlias> duplicateAliases(List<WebSourceAlias> aliases) {
    return aliases.stream()
        .map(
            alias ->
                "http_redirect".equals(alias.evidenceType())
                    ? new WebSourceAlias(
                        alias.url(),
                        alias.title(),
                        alias.publishedAt(),
                        "duplicate_body_http_redirect")
                    : alias)
        .toList();
  }
}
