package dev.mikoto2000.rei.memory.service;

import java.util.*;

/** Bound plain-text terms; Japanese text also contributes trigrams without needing a morphological engine. */
final class MemorySearchTerms {
  private MemorySearchTerms() { }
  static List<String> of(String query) {
    if(query==null) return List.of();
    var terms=new LinkedHashSet<String>();
    for(String term:query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
      if(term.isBlank()) continue;
      terms.add(term);
      int[] points=term.codePoints().toArray();
      if(term.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*"))
        for(int i=0;i+3<=points.length && terms.size()<20;i++) terms.add(new String(points,i,3));
      if(terms.size()>=20) break;
    }
    return terms.stream().limit(20).toList();
  }
}
