package dev.mikoto2000.rei.artifact;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.TextChangeSetService;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.paper.PaperLibraryService;
import dev.mikoto2000.rei.paper.PaperOperation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 成果物の管理に必要な成果物取得元書き出しを扱う。
 *
 * <p>Explicit local export to a validated Session; source paths are never supplied by a client.
 */
public final class ArtifactSourceExport {
  /** 保存先。 */
  private final ArtifactStore store;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionLifecycle sessions;

  /** 変更一覧。 */
  private final TextChangeSetService changes;

  /** 論文一覧。 */
  private final PaperLibraryService papers;

  /** 指定された値や利用先を使って、ArtifactSourceExportを初期化する。 */
  public ArtifactSourceExport(
      ArtifactStore store,
      ProjectRegistry projects,
      SessionLifecycle sessions,
      TextChangeSetService changes,
      PaperLibraryService papers) {
    this.store = store;
    this.projects = projects;
    this.sessions = sessions;
    this.changes = changes;
    this.papers = papers;
  }

  /** 成果物の管理で用いる書き出しを返す。 */
  public Artifact export(String project, String session, String source, String id, String version)
      throws IOException {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (session == null || session.isBlank() || id == null || id.length() > 256 || source == null)
      throw new IllegalArgumentException("Export source and Session required");
    var owner =
        projects.resolveById(project).orElseThrow(() -> new ResourceNotFoundException("Artifact"));
    try {
      sessions.validate(session, project);
    } catch (dev.mikoto2000.rei.application.run.SessionConflictException foreign) {
      throw new ResourceNotFoundException("Artifact");
    }
    String media, filename;
    byte[] bytes;
    if (source.equals("CHANGE_SET_PROPOSAL")) {
      if (changes == null) throw new ResourceNotFoundException("Artifact source");
      if (version != null)
        throw new IllegalArgumentException("Proposal version is its immutable identity");
      bytes = changes.exportProposal(owner, id);
      media = "text/plain";
      filename = "proposal-" + canonical(id) + ".txt";
    } else if (source.startsWith("PAPER_")) {
      if (papers == null) throw new ResourceNotFoundException("Artifact source");
      var saved =
          papers.exportCached(
              canonical(id),
              source.substring(6).toLowerCase(java.util.Locale.ROOT),
              version,
              PaperOperation.local(session));
      bytes = saved.bytes();
      media = saved.mediaType();
      filename = saved.filename();
    } else throw new IllegalArgumentException("Unsupported Artifact source");
    String key;
    try {
      key =
          "export:"
              + HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(
                              (source + "\0" + id + "\0" + version)
                                  .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    return store.publishSession(owner, session, key, media, filename, bytes);
  }

  /** 成果物の管理で用いる正規を返す。 */
  private static String canonical(String id) {
    if (!java.util.UUID.fromString(id).toString().equals(id))
      throw new IllegalArgumentException("Canonical source ID required");
    return id;
  }
}
