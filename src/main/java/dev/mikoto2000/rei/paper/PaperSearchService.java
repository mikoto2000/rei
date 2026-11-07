package dev.mikoto2000.rei.paper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;

/** 論文検索のユースケースを実現する。 */
@Service
public class PaperSearchService {
  /** 主要な。 */
  private final OpenAlexSearchProvider primary;

  /** 代替手段。 */
  private final CrossrefSearchProvider fallback;

  /** リポジトリ。 */
  private final BiFunction<Paper, PaperOperation, Paper> savePaper;

  /** 参照先一覧。 */
  private final BiConsumer<String, List<String>> replaceReferences;

  /** 設定。 */
  private final IntSupplier maxSearchLimit;

  /** 指定された値や利用先を使って、PaperSearchServiceを初期化する。 */
  public PaperSearchService(
      OpenAlexSearchProvider primary,
      CrossrefSearchProvider fallback,
      PaperRepository repo,
      PaperSessionReferences refs,
      PaperProperties config) {
    this.primary = primary;
    this.fallback = fallback;
    this.savePaper = (paper, op) -> repo.save(paper, op);
    this.replaceReferences = (session, ids) -> refs.replace(session, ids);
    this.maxSearchLimit = () -> config.getMaxLimit();
  }

  /**
   * 論文の調査と整理で受け渡す結果を表す。
   *
   * @param papers 論文一覧
   * @param warnings 注意事項
   */
  public record Result(List<Paper> papers, List<String> warnings) {
    public Result {
      papers = papers == null ? null : Collections.unmodifiableList(new ArrayList<>(papers));
      warnings = warnings == null ? null : Collections.unmodifiableList(new ArrayList<>(warnings));
    }
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public Result search(PaperSearchQuery q, PaperOperation op) {
    op.check();
    if (q.limit() > maxSearchLimit.getAsInt())
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
      var s = savePaper.apply(p, op);
      saved.put(s.id(), s);
    }
    replaceReferences.accept(op.sessionId(), List.copyOf(saved.keySet()));
    org.slf4j.LoggerFactory.getLogger(getClass())
        .info(
            "paper search query={} results={} dedup={}",
            q.query().replaceAll("[\\r\\n]", " "),
            saved.size(),
            papers.size() - saved.size());
    return new Result(List.copyOf(saved.values()), warnings);
  }

  /** 論文の調査と整理で用いるenrichを返す。 */
  public Paper enrich(Paper paper, PaperOperation op) {
    if (paper.doi() == null) return paper;
    var extra = fallback.metadata(paper.doi(), op);
    return savePaper.apply(extra, op);
  }
}
