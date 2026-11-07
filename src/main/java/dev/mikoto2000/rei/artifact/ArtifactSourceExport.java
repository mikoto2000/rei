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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 成果物の管理に必要な成果物取得元書き出しを扱う。
 *
 * <p>Explicit local export to a validated Session; source paths are never supplied by a client.
 */
public final class ArtifactSourceExport {
  /** 保存先。 */
  private final BiFunction<String, String, ExportTarget> prepareTarget;

  /** 論文一覧。 */
  private final Function<PaperExport, PaperLibraryService.CachedExport> readPaper;

  /** 指定された値や利用先を使って、ArtifactSourceExportを初期化する。 */
  public ArtifactSourceExport(
      ArtifactStore store,
      ProjectRegistry projects,
      SessionLifecycle sessions,
      TextChangeSetService changes,
      PaperLibraryService papers) {
    this.prepareTarget =
        (project, session) -> {
          var owner =
              projects
                  .resolveById(project)
                  .orElseThrow(() -> new ResourceNotFoundException("Artifact"));
          try {
            sessions.validate(session, project);
          } catch (dev.mikoto2000.rei.application.run.SessionConflictException foreign) {
            throw new ResourceNotFoundException("Artifact");
          }
          return new ExportTarget(
              (key, media, filename, bytes) ->
                  store.publishSession(owner, session, key, media, filename, bytes),
              changes == null ? null : id -> changes.exportProposal(owner, id));
        };
    this.readPaper =
        papers == null
            ? null
            : request ->
                papers.exportCached(
                    request.id(),
                    request.kind(),
                    request.version(),
                    PaperOperation.local(request.session()));
  }

  /** 成果物の管理で用いる書き出しを返す。 */
  public Artifact export(String project, String session, String source, String id, String version)
      throws IOException {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (session == null || session.isBlank() || id == null || id.length() > 256 || source == null)
      throw new IllegalArgumentException("Export source and Session required");
    var target = prepareTarget.apply(project, session);
    String media, filename;
    byte[] bytes;
    if (source.equals("CHANGE_SET_PROPOSAL")) {
      if (target.proposal() == null) throw new ResourceNotFoundException("Artifact source");
      if (version != null)
        throw new IllegalArgumentException("Proposal version is its immutable identity");
      bytes = target.proposal().read(id);
      media = "text/plain";
      filename = "proposal-" + canonical(id) + ".txt";
    } else if (source.startsWith("PAPER_")) {
      if (readPaper == null) throw new ResourceNotFoundException("Artifact source");
      var saved =
          readPaper.apply(
              new PaperExport(
                  canonical(id),
                  source.substring(6).toLowerCase(java.util.Locale.ROOT),
                  version,
                  session));
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
    return target.publish().publish(key, media, filename, bytes);
  }

  /** 成果物の管理で用いる正規を返す。 */
  private static String canonical(String id) {
    if (!java.util.UUID.fromString(id).toString().equals(id))
      throw new IllegalArgumentException("Canonical source ID required");
    return id;
  }

  private record PaperExport(String id, String kind, String version, String session) {}

  private record ExportTarget(PublishExport publish, ReadProposal proposal) {}

  @FunctionalInterface
  private interface PublishExport {
    Artifact publish(String key, String mediaType, String filename, byte[] bytes);
  }

  @FunctionalInterface
  private interface ReadProposal {
    byte[] read(String id) throws IOException;
  }
}
