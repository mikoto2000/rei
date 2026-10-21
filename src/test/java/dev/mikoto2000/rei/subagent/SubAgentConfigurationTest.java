package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 補助エージェント機能の構成の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SubAgentConfigurationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う判断基準。 */
  final SubAgentToolPolicy policy =
      new SubAgentToolPolicy(Set.of("readMultiFile", "runCommand", "delegateTask"));

  /** 検証に使う読み込みの窓口。 */
  final SubAgentDefinitionLoader loader =
      new SubAgentDefinitionLoader(policy, model -> model.equals("known"));

  /** {@code yaml} の条件を再現するための補助情報を返す。 */
  static String yaml(String id) {
    return """
        id: %s
        name: Reviewer
        description: Independent review
        systemPrompt: |
          Review independently.
        tools: [readMultiFile]
        maxSteps: 2
        timeout: 120s
        """
        .formatted(id);
  }

  /** 検証用に、指定された内容を書き込み先へ反映する。 */
  Path write(String file, String yaml) throws Exception {
    return Files.writeString(directory.resolve(file), yaml);
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsSafeDefinitionWithOptionalModel() throws Exception {
    var definition = loader.load(write("a.yaml", yaml("reviewer")));
    assertThat(definition.id()).isEqualTo("reviewer");
    assertThat(definition.model()).isNull();
    assertThat(definition.timeout()).isEqualTo(java.time.Duration.ofSeconds(120));
    assertThat(loader.load(write("b.yaml", yaml("reviewer") + "model: known\n")).model())
        .isEqualTo("known");
  }

  /** 根拠ツール一覧を必要とすることを確認する。 */
  @Test
  void semanticValidationIsOptInBooleanAndRequiresEvidenceTools() throws Exception {
    assertThat(loader.load(write("default.yaml", yaml("reviewer"))).semanticValidation()).isFalse();
    assertThat(
            loader
                .load(
                    write(
                        "enabled.yaml",
                        yaml("reviewer")
                            + "evidenceTools: [readMultiFile]\nsemanticValidation: true\n"))
                .semanticValidation())
        .isTrue();
    for (String value : List.of("true", "null", "1", "wrong")) {
      var file =
          write("bad-semantic.yaml", yaml("reviewer") + "semanticValidation: " + value + "\n");
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("semanticValidation");
    }
  }

  /** 補助エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void parentApprovalInheritanceIsAnExplicitBoolean() throws Exception {
    assertThat(loader.load(write("default.yaml", yaml("reviewer"))).inheritApprovals()).isFalse();
    assertThat(
            loader
                .load(write("enabled.yaml", yaml("reviewer") + "inheritApprovals: true\n"))
                .inheritApprovals())
        .isTrue();
    for (String value : List.of("null", "1", "wrong")) {
      var file = write("bad-approval.yaml", yaml("reviewer") + "inheritApprovals: " + value + "\n");
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("inheritApprovals");
    }
    var forbidden =
        write(
            "forbidden.yaml",
            yaml("reviewer").replace("readMultiFile", "runCommand") + "inheritApprovals: true\n");
    assertThatThrownBy(() -> loader.load(forbidden)).hasMessageContaining("not permitted");
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void evidenceToolsAreOptInAndMustBeUniqueRequestedTools() throws Exception {
    assertThat(loader.load(write("a.yaml", yaml("reviewer"))).evidenceTools()).isEmpty();
    assertThat(
            loader
                .load(write("a.yaml", yaml("reviewer") + "evidenceTools: [readMultiFile]\n"))
                .evidenceTools())
        .containsExactly("readMultiFile");
    for (String value : List.of("[missing]", "[readMultiFile, readMultiFile]", "wrong", "[true]")) {
      var file = write("invalid.yaml", yaml("reviewer") + "evidenceTools: " + value + "\n");
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("evidenceTools");
    }
  }

  /** 必須呼び出し回数読み込み完全一致のJSON変換引数およびについて、未観測ツール一覧を受け付けないことを確認する。 */
  @Test
  void requiredCallsLoadExactJsonArgumentsAndRejectUnobservedToolsAndUnsafeValues()
      throws Exception {
    String extra =
        "evidenceTools: [readMultiFile]\nrequiredToolCalls:\n  - tool: readMultiFile\n    arguments: {paths: [README.md]}\n";
    var definition = loader.load(write("calls.yaml", yaml("reviewer") + extra));
    assertThat(definition.requiredToolCalls()).hasSize(1);
    assertThat(definition.requiredToolCalls().getFirst().argumentsJson())
        .isEqualTo("{\"paths\":[\"README.md\"]}");
    assertThat(loader.load(write("default.yaml", yaml("reviewer"))).requiredToolCalls()).isEmpty();
    for (String bad :
        List.of(
            extra.replace("tool: readMultiFile", "tool: missing"),
            extra.replace("arguments: {paths: [README.md]}", "arguments: []"),
            extra.replace("arguments: {paths: [README.md]}", "arguments: {date: 2026-01-01}"),
            extra.replace("evidenceTools: [readMultiFile]\n", ""))) {
      var file = write("bad-calls.yaml", yaml("reviewer") + bad);
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("requiredToolCalls");
    }
  }

  /** 修復上限既定値終点無効状態およびについて、不正な値一覧を受け付けないことを確認する。 */
  @Test
  void repairLimitDefaultsToDisabledAndRejectsInvalidValues() throws Exception {
    assertThat(loader.load(write("a.yaml", yaml("reviewer"))).maxRepairs()).isZero();
    assertThat(loader.load(write("a.yaml", yaml("reviewer") + "maxRepairs: 3\n")).maxRepairs())
        .isEqualTo(3);
    for (String value : List.of("-1", "4", "true", "null", "1.5", "wrong")) {
      var file = write("invalid.yaml", yaml("reviewer") + "maxRepairs: " + value + "\n");
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("maxRepairs");
    }
  }

  /** 空の未指定のを受け付けないことを確認する。 */
  @Test
  void expectedOutputLoadsTypedFieldsAndRejectsEmptyNullAndUnknownFields() throws Exception {
    String extra =
        "evidenceTools: [readMultiFile]\nrequiredToolCalls:\n  - tool: readMultiFile\n    arguments: {}\n    expectedOutput: {found: true, count: 0}\n";
    var definition = loader.load(write("outcome.yaml", yaml("reviewer") + extra));
    assertThat(definition.requiredToolCalls().getFirst().expectedOutputJson())
        .isEqualTo("{\"found\":true,\"count\":0}");
    for (String value : List.of("{}", "[]", "null", "{date: 2026-01-01}")) {
      var file =
          write(
              "bad-outcome.yaml",
              yaml("reviewer") + extra.replace("{found: true, count: 0}", value));
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("requiredToolCalls");
    }
    var unknown =
        write("unknown-outcome.yaml", yaml("reviewer") + extra + "    unexpected: true\n");
    assertThatThrownBy(() -> loader.load(unknown)).hasMessageContaining("requiredToolCalls");
  }

  /** 不正な項目一覧を受け付けないことを確認する。 */
  @Test
  void rejectsInvalidFieldsWithFileAndField() throws Exception {
    for (String field :
        List.of("id", "name", "description", "systemPrompt", "maxSteps", "timeout")) {
      String bad = yaml("reviewer").replaceAll("(?m)^" + field + ":.*\\n(?:  .*\\n)*", "");
      Path path = write("invalid.yaml", bad);
      assertThatThrownBy(() -> loader.load(path))
          .hasMessageContaining("invalid.yaml")
          .hasMessageContaining(field);
    }
    for (String replacement : List.of("0", "-1", "wrong")) {
      Path path =
          write(
              "invalid.yaml", yaml("reviewer").replace("maxSteps: 2", "maxSteps: " + replacement));
      assertThatThrownBy(() -> loader.load(path)).hasMessageContaining("maxSteps");
    }
    for (String replacement : List.of("0s", "-1s", "forever", "999999999999999999999s")) {
      Path path = write("invalid.yaml", yaml("reviewer").replace("120s", replacement));
      assertThatThrownBy(() -> loader.load(path)).hasMessageContaining("timeout");
    }
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsUnknownProhibitedAndRecursiveToolsAndUnknownModels() throws Exception {
    for (String tool : List.of("missing", "runCommand", "delegateTask")) {
      Path file = write("invalid.yaml", yaml("reviewer").replace("readMultiFile", tool));
      assertThatThrownBy(() -> loader.load(file))
          .hasMessageContaining("tools")
          .hasMessageContaining(tool);
    }
    assertThatThrownBy(
            () -> loader.load(write("invalid.yaml", yaml("reviewer") + "model: missing\n")))
        .hasMessageContaining("model");
  }

  /** 不明キー一覧重複の有無キー一覧を受け付けないことを確認する。 */
  @Test
  void rejectsUnknownKeysDuplicateKeysAndJavaTags() throws Exception {
    for (String extra :
        List.of(
            "implementation: arbitrary\n",
            "null: value\n",
            "id: other\n",
            "model: !!java.net.URL [https://example.com]\n")) {
      assertThatThrownBy(() -> loader.load(write("invalid.yaml", yaml("reviewer") + extra)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid.yaml");
    }
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void policyIntersectionIsFailClosed() {
    assertThat(
            policy.effectiveTools(
                List.of("readMultiFile", "runCommand", "delegateTask", "missing")))
        .containsExactly("readMultiFile");
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void registryReloadIsAtomicAndOrdered() throws Exception {
    write("z.yaml", yaml("z"));
    var registry = new SubAgentRegistry(directory, loader);
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.findById("z")).isPresent();
    write("a.yaml", yaml("a"));
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.list()).extracting(SubAgentDefinition::id).containsExactly("a", "z");
    write("broken.yaml", yaml("z"));
    assertThat(registry.reload())
        .anyMatch(error -> error.contains("duplicate id") && error.contains("broken.yaml"));
    assertThat(registry.list()).extracting(SubAgentDefinition::id).containsExactly("a", "z");
    Files.delete(directory.resolve("broken.yaml"));
    Files.delete(directory.resolve("z.yaml"));
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.findById("z")).isEmpty();
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void bundledSamplesOnlyRequestActualAuditedTools() {
    var catalog =
        new SubAgentToolCatalog(
            org.mockito.Mockito.mock(dev.mikoto2000.rei.search.SearchTools.class),
            org.mockito.Mockito.mock(dev.mikoto2000.rei.websearch.WebSearchTools.class));
    var samples =
        new SubAgentRegistry(
            Path.of("config/subagents"),
            new SubAgentDefinitionLoader(new SubAgentToolPolicy(catalog.knownNames()), m -> false));
    assertThat(samples.reload()).isEmpty();
    assertThat(samples.list())
        .extracting(SubAgentDefinition::id)
        .containsExactly("document-editor", "researcher", "reviewer");
  }
}
