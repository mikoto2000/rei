package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

/** 組み込みツール分類の結果と状態の契約を検証するテスト。 */
class BuiltinToolClassificationTest {
  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void everyBuiltinAnnotatedToolHasAnExplicitClassification() throws Exception {
    var resources = new PathMatchingResourcePatternResolver();
    var metadata = new CachingMetadataReaderFactory(resources);
    var names = new TreeSet<String>();
    String production =
        ToolPermissionPolicy.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toExternalForm();
    for (var resource : resources.getResources(production + "dev/mikoto2000/rei/**/*.class")) {
      // Inspect metadata without initializing beans, launching processes or requiring accounts.
      var type = metadata.getMetadataReader(resource).getAnnotationMetadata();
      for (var method : type.getAnnotatedMethods(Tool.class.getName())) {
        var attributes = method.getAnnotationAttributes(Tool.class.getName());
        String name = (String) attributes.get("name");
        names.add(name == null || name.isBlank() ? method.getMethodName() : name);
      }
    }
    assertTrue(names.size() > 100, "Tool inventory unexpectedly empty or incomplete");
    assertEquals(
        List.of(),
        names.stream().filter(name -> !ToolPermissionPolicy.classified(name)).toList(),
        "New built-in tools require an audited capability classification");
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void readingWritingSendingAndArbitraryExecutionKeepDistinctPermissions() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("taskList"));
    assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("workContextGet"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("taskCreate"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("blueskyPost"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("requestCodexReview"));
    assertEquals(
        Set.of(ActionCapability.EXECUTE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        policy.capabilities("soundNotify"));
    for (String tool :
        List.of(
            "taskDelete",
            "runCommand",
            "selfRepairPatch",
            "computerUse",
            "checkpointResume",
            "unknownMcpTool")) assertEquals(PermissionDecision.DENY, policy.evaluate(tool), tool);
  }
}
