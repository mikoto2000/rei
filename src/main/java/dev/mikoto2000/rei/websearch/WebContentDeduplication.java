package dev.mikoto2000.rei.websearch;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class WebContentDeduplication {
  private WebContentDeduplication() {}
  public static String fingerprint(String fullContent) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fullContent.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  public static List<WebSearchPage> pages(List<WebSearchPage> pages) {
    List<WebSearchPage> result = new ArrayList<>(); Map<String, Integer> seen = new HashMap<>();
    for (var page : pages) {
      Integer index = page.fingerprint() == null ? null : seen.get(page.fingerprint());
      if (index == null) {
        if (page.fingerprint() != null) seen.put(page.fingerprint(), result.size());
        result.add(page);
      } else {
        WebSearchMetrics.OBSERVED.add("duplicate_content", 1);
        result.set(index, result.get(index).withAliases(page.aliases()));
      }
    }
    return List.copyOf(result);
  }
  public static List<WebSearchAndReadItem> items(List<WebSearchAndReadItem> items) {
    List<WebSearchAndReadItem> result = new ArrayList<>(); Map<String, Integer> seen = new HashMap<>();
    for (var item : items) {
      Integer index = item.fingerprint() == null ? null : seen.get(item.fingerprint());
      if (index == null) {
        if (item.fingerprint() != null) seen.put(item.fingerprint(), result.size());
        result.add(item);
      } else {
        WebSearchMetrics.OBSERVED.add("duplicate_content", 1);
        result.set(index, result.get(index).withAliases(item.aliases()));
      }
    }
    return List.copyOf(result);
  }
}
