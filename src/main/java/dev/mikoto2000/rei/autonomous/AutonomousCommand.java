package dev.mikoto2000.rei.autonomous;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Manual draft consideration and explicit topic consent. No publication operations. */
@Component
@Command(name = "autonomous", description = "自発投稿案の手動生成と公開用話題の管理", mixinStandardHelpOptions = true)
public final class AutonomousCommand implements java.util.concurrent.Callable<Integer> {
  private final AutonomousPublicSources sources;
  private final Supplier<AutonomousGenerationService> generation;

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "sources",
      paramLabel = "generate|sources|source|add-source|edit-source|permit-source|revoke-source")
  String action;

  @Parameters(index = "1", arity = "0..1", paramLabel = "sourceId")
  String id;

  @Option(names = "--text", description = "話題の本文（引用符で囲んで指定）")
  String text;

  @Option(names = "--revision", description = "確認した話題のリビジョン（編集・許可・取消で必須）")
  Integer revision;

  @Option(names = "--public-use", description = "新規登録する本文を投稿案生成に利用してよいと明示する")
  boolean publicUse;

  @Option(names = "--limit", defaultValue = "20", description = "一覧の件数（1～200）")
  int limit;

  public AutonomousCommand() {
    this(null, (Supplier<AutonomousGenerationService>) () -> null);
  }

  @Autowired
  public AutonomousCommand(
      AutonomousPublicSources sources, ObjectProvider<AutonomousGenerationService> generation) {
    this(sources, generation::getIfAvailable);
  }

  AutonomousCommand(
      AutonomousPublicSources sources, Supplier<AutonomousGenerationService> generation) {
    this.sources = sources;
    this.generation = generation;
  }

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    try {
      if (publicUse && !"add-source".equals(action))
        throw new IllegalArgumentException("--public-use is only valid for add-source");
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
      out.println(dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(result));
      return result instanceof AutonomousGenerationService.Result generated
              && generated.status() == AutonomousGenerationService.Status.FAILED
          ? 2
          : 0;
    } catch (Exception failed) {
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
