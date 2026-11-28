package dev.mikoto2000.rei.autonomous;

import java.util.function.Supplier;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Manual review, selected-draft publication, and explicit topic consent. */
@Component
@RegisterReflectionForBinding({
  AutonomousDraft.class,
  AutonomousPublicSources.Source.class,
  AutonomousGenerationService.Result.class
})
@Command(
    name = "autonomous",
    description = "自発投稿案の確認・編集・手動生成と公開用話題の管理",
    mixinStandardHelpOptions = true)
public final class AutonomousCommand implements java.util.concurrent.Callable<Integer> {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final AutonomousPublicSources sources;
  private final AutonomousDraftStore drafts;
  private final Supplier<AutonomousGenerationService> generation;
  private final Supplier<AutonomousPublicationService> publication;

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "sources",
      paramLabel =
          "generate|drafts|draft|edit|regenerate|reject|hold|publish|reconcile|sources|source|add-source|edit-source|permit-source|revoke-source")
  String action;

  @Parameters(index = "1", arity = "0..1", paramLabel = "id")
  String id;

  @Option(names = "--text", description = "投稿案または話題の本文（引用符で囲んで指定）")
  String text;

  @Option(names = "--revision", description = "確認したリビジョン（編集・再生成・見送り・公開・照合・許可・取消で必須）")
  Integer revision;

  @Option(names = "--reason", description = "任意の見送り理由（非公開）")
  String reason;

  @Option(names = "--public-use", description = "新規登録する本文を投稿案生成に利用してよいと明示する")
  boolean publicUse;

  @Option(names = "--limit", defaultValue = "20", description = "一覧の件数（1～200）")
  int limit;

  public AutonomousCommand() {
    this(null, null, (Supplier<AutonomousGenerationService>) () -> null);
  }

  public AutonomousCommand(
      AutonomousPublicSources sources, ObjectProvider<AutonomousGenerationService> generation) {
    this(sources, null, generation::getIfAvailable);
  }

  public AutonomousCommand(
      AutonomousPublicSources sources,
      AutonomousDraftStore drafts,
      ObjectProvider<AutonomousGenerationService> generation) {
    this(sources, drafts, generation::getIfAvailable);
  }

  @Autowired
  public AutonomousCommand(
      AutonomousPublicSources sources,
      AutonomousDraftStore drafts,
      ObjectProvider<AutonomousGenerationService> generation,
      ObjectProvider<AutonomousPublicationService> publication) {
    this(sources, drafts, generation::getIfAvailable, publication::getIfAvailable);
  }

  AutonomousCommand(
      AutonomousPublicSources sources, Supplier<AutonomousGenerationService> generation) {
    this(sources, null, generation);
  }

  AutonomousCommand(
      AutonomousPublicSources sources,
      AutonomousDraftStore drafts,
      Supplier<AutonomousGenerationService> generation) {
    this(sources, drafts, generation, () -> null);
  }

  AutonomousCommand(
      AutonomousPublicSources sources,
      AutonomousDraftStore drafts,
      Supplier<AutonomousGenerationService> generation,
      Supplier<AutonomousPublicationService> publication) {
    this.sources = sources;
    this.drafts = drafts;
    this.generation = generation;
    this.publication = publication;
  }

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    try {
      if (publicUse && !"add-source".equals(action))
        throw new IllegalArgumentException("--public-use is only valid for add-source");
      if (reason != null && !"reject".equals(action))
        throw new IllegalArgumentException("--reason is only valid for reject");
      if (text != null && ("publish".equals(action) || "reconcile".equals(action)))
        throw new IllegalArgumentException("Publication uses only the saved draft text");
      Object result =
          switch (action) {
            case "generate" -> {
              var service = generation.get();
              yield service == null
                  ? new AutonomousGenerationService.Result(
                      AutonomousGenerationService.Status.SKIPPED, "disabled", null)
                  : service.manual();
            }
            case "sources" -> sources.list(limit);
            case "drafts" -> drafts.list(limit);
            case "draft", "hold" -> drafts.get(requiredId());
            case "publish" -> publication().publish(requiredId(), requiredRevision(), "shell");
            case "reconcile" -> publication().reconcile(requiredId(), requiredRevision(), "shell");
            case "edit" -> drafts.edit(requiredId(), requiredRevision(), requiredText());
            case "reject" ->
                drafts.reject(requiredId(), requiredRevision(), reason == null ? "" : reason);
            case "regenerate" -> {
              var draftId = requiredId();
              var expected = requiredRevision();
              var service = generation.get();
              yield service == null
                  ? new AutonomousGenerationService.Result(
                      AutonomousGenerationService.Status.SKIPPED,
                      "disabled",
                      draftId,
                      drafts.get(draftId))
                  : service.regenerate(draftId, expected);
            }
            case "source" -> sources.get(requiredId());
            case "add-source" -> sources.register(requiredText(), publicUse, "shell");
            case "edit-source" ->
                sources.edit(requiredId(), requiredRevision(), requiredText(), "shell");
            case "permit-source" ->
                sources.permission(requiredId(), requiredRevision(), true, "shell");
            case "revoke-source" ->
                sources.permission(requiredId(), requiredRevision(), false, "shell");
            default ->
                throw new IllegalArgumentException(
                    "Unknown autonomous command; use /autonomous --help");
          };
      out.println(JSON.writeValueAsString(result));
      return result instanceof AutonomousGenerationService.Result generated
              && generated.status() == AutonomousGenerationService.Status.FAILED
          ? 2
          : 0;
    } catch (java.io.IOException | RuntimeException failed) {
      // Topic bodies and provider errors may contain private data or terminal control characters.
      out.println("[error] 自発投稿案の操作に失敗しました。引数と現在のリビジョンを確認してください。");
      return 2;
    } finally {
      out.flush();
    }
  }

  private String requiredId() {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("sourceId is required");
    return id;
  }

  private AutonomousPublicationService publication() {
    var service = publication.get();
    if (service == null) throw new SecurityException("Publication is unavailable");
    return service;
  }

  private String requiredText() {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("--text is required");
    return text;
  }

  private int requiredRevision() {
    if (revision == null || revision < 1)
      throw new IllegalArgumentException("--revision is required");
    return revision;
  }
}
