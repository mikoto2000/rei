package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.skills.AgentSkillPromptRenderer;
import dev.mikoto2000.rei.skills.AgentSkillsProperties;
import dev.mikoto2000.rei.skills.FileSystemAgentSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** スキル補助資料読み取りの結果と状態の契約を検証するテスト。 */
class SkillResourceReadTest {
  /** 検証用に、必要な内容を参照できるよう、単一を読み取る。 */
  private static Tools.ReadFileResult readOne(
      Tools tools, String skillName, Tools.ReadFileRequest file) throws Exception {
    return tools.readSkillResource(skillName, List.of(file)).getFirst();
  }

  /** 複数補助資料一覧を読み取ることを確認する。 */
  @Test
  void readsMultipleResourcesWithIndependentRangesAndErrors() throws Exception {
    Tools tools = tools();
    Files.writeString(temporary.resolve("skills/review/references/other.md"), "alpha\nbeta\n");
    Files.writeString(temporary.resolve("skills/review/.env"), "SECRET=private");
    var results =
        tools.readSkillResource(
            "review",
            List.of(
                new Tools.ReadFileRequest("references/guide.md", 2, 2),
                new Tools.ReadFileRequest("references/other.md", 1, 1),
                new Tools.ReadFileRequest("../outside.md", null, null),
                new Tools.ReadFileRequest(".env", null, null)));
    assertThat(results).hasSize(4);
    assertThat(results.get(0).path()).isEqualTo("references/guide.md");
    assertThat(results.get(0).content()).hasSize(1).anyMatch(line -> line.contains("second"));
    assertThat(results.get(1).path()).isEqualTo("references/other.md");
    assertThat(results.get(1).content()).hasSize(1).anyMatch(line -> line.contains("alpha"));
    assertThat(results.get(2).error()).contains("outside Project");
    assertThat(results.get(3).error()).contains("Excluded");
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void validatesBatchSizeAndEveryPathBeforeReading() throws Exception {
    Tools tools = tools();
    var valid = new Tools.ReadFileRequest("references/guide.md", null, null);
    assertThatThrownBy(() -> tools.readSkillResource("review", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.readSkillResource("review", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> tools.readSkillResource("review", java.util.Collections.nCopies(21, valid)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> tools.readSkillResource("review", java.util.Arrays.asList(valid, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tools.readSkillResource(
                    "review",
                    List.of(
                        valid,
                        new Tools.ReadFileRequest(
                            temporary.resolve("outside.md").toString(), null, null))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void continuesMultipleLongResourcesInOneBatch() throws Exception {
    Tools tools = tools();
    Files.writeString(
        temporary.resolve("skills/review/references/guide.md"), "guide\n".repeat(1200));
    Files.writeString(
        temporary.resolve("skills/review/references/other.md"), "other\n".repeat(1200));
    var first =
        tools.readSkillResource(
            "review",
            List.of(
                new Tools.ReadFileRequest("references/guide.md", null, null),
                new Tools.ReadFileRequest("references/other.md", null, null)));
    assertThat(first)
        .allSatisfy(
            result -> {
              assertThat(result.error()).isNull();
              assertThat(result.truncated()).isTrue();
              assertThat(result.nextRead()).isNotNull();
            });
    var next =
        tools.readSkillResource(
            "review", first.stream().map(Tools.ReadFileResult::nextRead).toList());
    assertThat(next)
        .hasSize(2)
        .allSatisfy(
            result -> {
              assertThat(result.error()).isNull();
              assertThat(result.content()).isNotEmpty();
              assertThat(result.startLine()).isGreaterThan(1);
              assertThat(result.truncated()).isFalse();
            });
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使うこの機能の動作設定。 */
  private AgentSkillsProperties properties;

  /** 検証に使う保存と検索の窓口。 */
  private FileSystemAgentSkillRepository repository;

  /** {@code tools} の条件を再現するための補助情報を返す。 */
  private Tools tools() throws Exception {
    Path skill = temporary.resolve("skills/review");
    Files.createDirectories(skill.resolve("references"));
    Files.writeString(
        skill.resolve("SKILL.md"),
        """
        ---
        name: review
        description: Review learning materials
        ---
        Read [guide](references/guide.md).
        """);
    Files.writeString(skill.resolve("references/guide.md"), "first\nsecond\nthird\n");
    properties = new AgentSkillsProperties();
    properties.setDirectories(List.of(temporary.resolve("skills").toString()));
    repository = new FileSystemAgentSkillRepository(properties);
    Tools tools = new Tools();
    tools.setSkillResources(repository, properties);
    return tools;
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void loadsReferencedResourceOutsideProjectWithoutRelaxingProjectReads() throws Exception {
    Tools tools = tools();
    Path project = Files.createDirectory(temporary.resolve("project"));
    Path guide = temporary.resolve("skills/review/references/guide.md");
    String prompt = new AgentSkillPromptRenderer().render("Review", repository.findEnabled());
    assertThat(prompt)
        .contains(
            "readSkillResource",
            "references/guide.md",
            temporary.resolve("skills/review").toString());
    var ordinaryRead =
        tools.readMultiFile(
            List.of(new Tools.ReadFileRequest(guide.toString(), null, null)), project);
    assertThat(ordinaryRead.getFirst().error()).contains("outside Project");
    var resource = readOne(tools, "review", new Tools.ReadFileRequest("references/guide.md", 2, 3));
    assertThat(resource.error()).isNull();
    assertThat(resource.content())
        .hasSize(2)
        .anyMatch(line -> line.contains("second"))
        .anyMatch(line -> line.contains("third"));
  }

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void rejectsEscapesAbsolutePathsSensitiveFilesAndUnknownSkills() throws Exception {
    Tools tools = tools();
    Files.writeString(temporary.resolve("skills/outside.md"), "private");
    Files.writeString(temporary.resolve("skills/review/.env"), "SECRET=private");
    assertThat(
            readOne(tools, "review", new Tools.ReadFileRequest("../outside.md", null, null))
                .error())
        .contains("outside Project");
    assertThat(readOne(tools, "review", new Tools.ReadFileRequest(".env", null, null)).error())
        .contains("Excluded");
    assertThatThrownBy(
            () ->
                readOne(
                    tools,
                    "review",
                    new Tools.ReadFileRequest(
                        temporary.resolve("skills/outside.md").toString(), null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> readOne(tools, "missing", new Tools.ReadFileRequest("guide.md", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            readOne(tools, "review", new Tools.ReadFileRequest("missing.md", null, null)).error())
        .isNotNull();
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void honorsDisabledSkillAndGlobalSetting() throws Exception {
    Tools tools = tools();
    Files.writeString(
        temporary.resolve("skills/review/SKILL.md"),
        """
        ---
        name: review
        description: Review
        enabled: false
        ---
        instructions
        """);
    repository.reload();
    assertThatThrownBy(
            () ->
                readOne(
                    tools, "review", new Tools.ReadFileRequest("references/guide.md", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    properties.setEnabled(false);
    assertThatThrownBy(
            () ->
                readOne(
                    tools, "review", new Tools.ReadFileRequest("references/guide.md", null, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 継続入力を返すことを確認する。 */
  @Test
  void returnsContinuationForLongReferences() throws Exception {
    Tools tools = tools();
    Files.writeString(
        temporary.resolve("skills/review/references/guide.md"), "line\n".repeat(1200));
    var result =
        readOne(tools, "review", new Tools.ReadFileRequest("references/guide.md", null, null));
    assertThat(result.error()).isNull();
    assertThat(result.truncated()).isTrue();
    assertThat(result.nextRead()).isNotNull();
    var continuation = readOne(tools, "review", result.nextRead());
    assertThat(continuation.error()).isNull();
    assertThat(continuation.content()).isNotEmpty();
    assertThat(continuation.startLine()).isGreaterThan(result.startLine());
  }

  /** 形式読み取りを読み取ることを確認する。 */
  @Test
  void classifiesReferenceReadAsReadOnly() {
    assertThat(ToolPermissionPolicy.intrinsicCapabilities("readSkillResource"))
        .containsExactly(ActionCapability.READ);
  }

  /** 共通の実行基盤で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void exposesSkillResourceToolSchema() throws Exception {
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools())
            .build()
            .getToolCallbacks();
    var callback =
        java.util.Arrays.stream(callbacks)
            .filter(tool -> tool.getToolDefinition().name().equals("readSkillResource"))
            .findFirst()
            .orElseThrow();
    assertThat(callback.getToolDefinition().inputSchema())
        .contains("skillName", "files", "path", "offset");
    var response =
        callback.call(
            """
        {"skillName":"review","files":[
          {"path":"references/guide.md","startLine":1,"endLine":1},
          {"path":"references/guide.md","startLine":3,"endLine":3}
        ]}
        """);
    assertThat(response).contains("first", "third").doesNotContain("second");
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void doesNotAllowMcpToReadPersonalSkillResourcesOutsideItsProject() throws Exception {
    Tools tools = tools();
    Path project = Files.createDirectory(temporary.resolve("project"));
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run",
            "conversation",
            project,
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP,
            dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY);
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      assertThat(
              readOne(tools, "review", new Tools.ReadFileRequest("references/guide.md", null, null))
                  .error())
          .contains("MCP excludes");
    }
  }
}
