package dev.mikoto2000.rei.paper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 論文保管資料のユースケースを実現する。 */
@Service
public class PaperLibraryService {
  /** リポジトリ。 */
  private final PaperRepository repo;

  /** 保存先。 */
  private final PaperArtifactStore store;

  /** 参照先一覧。 */
  private final PaperSessionReferences refs;

  /** 設定。 */
  private final PaperProperties config;

  /** 指定された値や利用先を使って、PaperLibraryServiceを初期化する。 */
  public PaperLibraryService(
      PaperRepository repo,
      PaperArtifactStore store,
      PaperSessionReferences refs,
      PaperProperties config) {
    this.repo = repo;
    this.store = store;
    this.refs = refs;
    this.config = config;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public void enabled() {
    if (!config.isEnabled())
      throw new PaperException(PaperException.Code.INVALID_QUERY, "Paper Research は無効です");
  }

  /** 論文の調査と整理で用いる説明を返す。 */
  public Object describe(Paper paper) {
    var versions = repo.versions(paper.id());
    boolean original =
        repo.artifactIds(paper.id()).stream().anyMatch(id -> store.exists(id, "originals", "pdf"));
    boolean extracted =
        repo.artifactIds(paper.id()).stream().anyMatch(id -> store.exists(id, "extracted", "json"));
    return Map.of(
        "paper",
        PaperViews.detail(paper),
        "hasOriginal",
        original,
        "hasExtractedContent",
        extracted,
        "hasSummary",
        versions.stream().anyMatch(v -> v.kind().equals("summary")),
        "hasTranslation",
        versions.stream().anyMatch(v -> v.kind().equals("translation")),
        "versions",
        versions);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Paper get(String ref, PaperOperation op) {
    enabled();
    op.check();
    return repo.find(refs.resolve(op.sessionId(), ref))
        .orElseThrow(() -> new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文が見つかりません"));
  }

  /**
   * 論文の調査と整理で受け渡す保存済み書き出しを表す。
   *
   * @param mediaType メディア種別
   * @param filename ファイル名
   * @param bytes バイト数
   */
  public record CachedExport(String mediaType, String filename, byte[] bytes) {
    /** 受け取った情報からCachedExportを作成する。 */
    public CachedExport {
      bytes = bytes.clone();
    }

    /** 論文の調査と整理で用いるバイト数を返す。 */
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  /**
   * 論文の調査と整理で用いる書き出し保存済みを返す。
   *
   * <p>Read a specifically selected saved artifact. This boundary never downloads or invokes a
   * model.
   */
  public CachedExport exportCached(String ref, String kind, String version, PaperOperation op) {
    var paper = get(ref, op);
    String id = paper.id();
    if (!Set.of("original", "extracted", "summary", "translation").contains(kind)
        || version != null && (version.isBlank() || version.length() > 256))
      throw new IllegalArgumentException("Invalid cached Paper export");
    if (kind.equals("summary")) {
      if (version == null) throw new IllegalArgumentException("Exact summary version required");
      var text =
          repo.artifact(id, "summary", version)
              .orElseThrow(
                  () -> new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND, "保存済み要約がありません"));
      return new CachedExport(
          "application/json",
          id + "-summary.json",
          text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    String folder, extension, media;
    if (kind.equals("translation")) {
      if (version == null) throw new IllegalArgumentException("Exact translation version required");
      extension =
          repo.artifact(id, "translation", version)
              .orElseThrow(
                  () -> new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND, "保存済み翻訳がありません"));
      folder = "translations";
      media = "application/json";
    } else {
      if (version != null)
        throw new IllegalArgumentException(
            "Original and extracted exports have no version argument");
      folder = kind.equals("original") ? "originals" : "extracted";
      extension = kind.equals("original") ? "pdf" : "json";
      media = kind.equals("original") ? "application/pdf" : "application/json";
    }
    for (String artifact : repo.artifactIds(id)) {
      op.check();
      var bytes = store.readBounded(artifact, folder, extension, 32L * 1024 * 1024);
      if (bytes.isPresent())
        return new CachedExport(
            media,
            id + "-" + kind + (media.equals("application/pdf") ? ".pdf" : ".json"),
            bytes.get());
    }
    throw new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND, "保存済み内容がありません");
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Paper> search(String query, int limit, PaperOperation op) {
    enabled();
    op.check();
    var result = repo.search(query, Math.min(limit, config.getMaxLibrarySearchResults()));
    refs.replace(op.sessionId(), result.stream().map(Paper::id).toList());
    return result;
  }

  /** 指定された対象を管理から取り除く。 */
  public void remove(String ref, boolean purge, PaperOperation op) {
    enabled();
    String resolved = refs.resolve(op.sessionId(), ref);
    String id = repo.canonicalId(resolved);
    PaperLocks.with(
        id,
        op,
        () -> {
          removeLocked(id, purge, op);
          return null;
        });
  }

  /** 対象を管理から外すため、使用中を取り除く。 */
  private void removeLocked(String id, boolean purge, PaperOperation op) {
    op.check();
    if (purge) {
      repo.beginPurge(id, op);
      for (String artifactId : repo.artifactIds(id)) store.purge(artifactId, op);
      repo.delete(id, op);
    } else repo.remove(id, op);
  }
}
