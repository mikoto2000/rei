package dev.mikoto2000.rei.paper;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaperSearchService {
  private final OpenAlexSearchProvider primary;
  private final CrossrefSearchProvider fallback;
  private final PaperRepository repo;
  private final PaperSessionReferences refs;
  private final PaperProperties config;

  public PaperSearchService(
      OpenAlexSearchProvider primary,
      CrossrefSearchProvider fallback,
      PaperRepository repo,
      PaperSessionReferences refs,
      PaperProperties config) {
    this.primary = primary;
    this.fallback = fallback;
    this.repo = repo;
    this.refs = refs;
    this.config = config;
  }

  public record Result(List<Paper> papers, List<String> warnings) {}

  public Result search(PaperSearchQuery q, PaperOperation op) {
    op.check();
    if (q.limit() > config.getMaxLimit())
      throw new PaperException(PaperException.Code.INVALID_QUERY, "設定された検索件数上限を超えています");
    List<String> warnings = new ArrayList<>();
    List<Paper> papers;
    try {
      papers = primary.search(q, op);
    } catch (PaperException e) {
      warnings.add("OpenAlex: " + e.code() + "。Crossref の結果のみ使用");
      papers = fallback.search(q, op);
    }
    Map<String, Paper> saved = new LinkedHashMap<>();
    for (Paper p : papers) {
      op.check();
      var s = repo.save(p, op);
      saved.put(s.id(), s);
    }
    refs.replace(op.sessionId(), List.copyOf(saved.keySet()));
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info(
            "paper search query={} results={} dedup={}",
            q.query().replaceAll("[\\r\\n]", " "),
            saved.size(),
            papers.size() - saved.size());
    return new Result(List.copyOf(saved.values()), warnings);
  }

  public Paper enrich(Paper paper, PaperOperation op) {
    if (paper.doi() == null) return paper;
    var extra = fallback.metadata(paper.doi(), op);
    return repo.save(extra, op);
  }
}
