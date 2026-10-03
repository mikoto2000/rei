package dev.mikoto2000.rei.web;
import java.util.List;
public record SearchResponse(String query, List<VectorHit> vectorResults, List<WebHit> webResults) {
  public record VectorHit(String docId, String source, int chunkIndex, Double score, String snippet) {}
  public record WebHit(String title, String url, String snippet, String publishedAt, String content, boolean truncated) {}
  public static SearchResponse from(dev.mikoto2000.rei.search.SearchKnowledgeResult r) {
    return new SearchResponse(r.query(), r.vectorResults().stream().map(v -> new VectorHit(v.docId(), v.source(), v.chunkIndex(), v.score(), v.snippet())).toList(),
        r.webContext().allResults().stream().map(w -> new WebHit(w.title(), w.url(), w.snippet(), w.publishedAt(), w.content(), w.truncated())).toList());
  }
}
