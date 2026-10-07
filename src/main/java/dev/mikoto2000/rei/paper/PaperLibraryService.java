package dev.mikoto2000.rei.paper;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaperLibraryService {
  private final PaperRepository repo;
  private final PaperArtifactStore store;
  private final PaperSessionReferences refs;
  private final PaperProperties config;

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

  public void enabled() {
    if (!config.isEnabled())
      throw new PaperException(PaperException.Code.INVALID_QUERY, "Paper Research は無効です");
  }

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

  public Paper get(String ref, PaperOperation op) {
    enabled();
    op.check();
    return repo.find(refs.resolve(op.sessionId(), ref))
        .orElseThrow(() -> new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文が見つかりません"));
  }
  public record CachedExport(String mediaType,String filename,byte[] bytes) {
    public CachedExport { bytes=bytes.clone(); }
    @Override public byte[] bytes(){return bytes.clone();}
  }
  /** Read a specifically selected saved artifact. This boundary never downloads or invokes a model. */
  public CachedExport exportCached(String ref,String kind,String version,PaperOperation op) {
    var paper=get(ref,op);String id=paper.id();
    if(!Set.of("original","extracted","summary","translation").contains(kind)
        ||version!=null&&(version.isBlank()||version.length()>256))throw new IllegalArgumentException("Invalid cached Paper export");
    if(kind.equals("summary")) {
      if(version==null)throw new IllegalArgumentException("Exact summary version required");
      var text=repo.artifact(id,"summary",version).orElseThrow(()->new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND,"保存済み要約がありません"));
      return new CachedExport("application/json",id+"-summary.json",text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    String folder,extension,media;
    if(kind.equals("translation")) {
      if(version==null)throw new IllegalArgumentException("Exact translation version required");
      extension=repo.artifact(id,"translation",version).orElseThrow(()->new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND,"保存済み翻訳がありません"));
      folder="translations";media="application/json";
    }else {
      if(version!=null)throw new IllegalArgumentException("Original and extracted exports have no version argument");
      folder=kind.equals("original")?"originals":"extracted";extension=kind.equals("original")?"pdf":"json";
      media=kind.equals("original")?"application/pdf":"application/json";
    }
    for(String artifact:repo.artifactIds(id)) {
      op.check();var bytes=store.readBounded(artifact,folder,extension,32L*1024*1024);
      if(bytes.isPresent())return new CachedExport(media,id+"-"+kind+(media.equals("application/pdf")?".pdf":".json"),bytes.get());
    }
    throw new PaperException(PaperException.Code.ARTIFACT_NOT_FOUND,"保存済み内容がありません");
  }

  public List<Paper> search(String query, int limit, PaperOperation op) {
    enabled();
    op.check();
    var result = repo.search(query, Math.min(limit, config.getMaxLibrarySearchResults()));
    refs.replace(op.sessionId(), result.stream().map(Paper::id).toList());
    return result;
  }

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

  private void removeLocked(String id, boolean purge, PaperOperation op) {
    op.check();
    if (purge) {
      repo.beginPurge(id, op);
      for (String artifactId : repo.artifactIds(id)) store.purge(artifactId, op);
      repo.delete(id, op);
    } else repo.remove(id, op);
  }
}
