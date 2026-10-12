package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.skills.AgentSkillPromptRenderer;
import dev.mikoto2000.rei.skills.AgentSkillsProperties;
import dev.mikoto2000.rei.skills.FileSystemAgentSkillRepository;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;

class SkillResourceReadTest {
  @TempDir Path temporary;

  private AgentSkillsProperties properties;
  private FileSystemAgentSkillRepository repository;

  private Tools tools() throws Exception {
    Path skill = temporary.resolve("skills/review");
    Files.createDirectories(skill.resolve("references"));
    Files.writeString(skill.resolve("SKILL.md"), """
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

  @Test
  void loadsReferencedResourceOutsideProjectWithoutRelaxingProjectReads() throws Exception {
    Tools tools = tools();
    Path project = Files.createDirectory(temporary.resolve("project"));
    Path guide = temporary.resolve("skills/review/references/guide.md");
    String prompt = new AgentSkillPromptRenderer().render("Review", repository.findEnabled());
    assertThat(prompt).contains("readSkillResource", "references/guide.md",
        temporary.resolve("skills/review").toString());
    var ordinaryRead = tools.readMultiFile(List.of(new Tools.ReadFileRequest(guide.toString(), null, null)), project);
    assertThat(ordinaryRead.getFirst().error()).contains("outside Project");
    var resource = tools.readSkillResource("review", new Tools.ReadFileRequest("references/guide.md", 2, 3));
    assertThat(resource.error()).isNull();
    assertThat(resource.content()).hasSize(2).anyMatch(line -> line.contains("second"))
        .anyMatch(line -> line.contains("third"));
  }

  @Test
  void rejectsEscapesAbsolutePathsSensitiveFilesAndUnknownSkills() throws Exception {
    Tools tools = tools();
    Files.writeString(temporary.resolve("skills/outside.md"), "private");
    Files.writeString(temporary.resolve("skills/review/.env"), "SECRET=private");
    assertThat(tools.readSkillResource("review", new Tools.ReadFileRequest("../outside.md", null, null)).error())
        .contains("outside Project");
    assertThat(tools.readSkillResource("review", new Tools.ReadFileRequest(".env", null, null)).error())
        .contains("Excluded");
    assertThatThrownBy(() -> tools.readSkillResource("review",
        new Tools.ReadFileRequest(temporary.resolve("skills/outside.md").toString(), null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.readSkillResource("missing", new Tools.ReadFileRequest("guide.md", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tools.readSkillResource("review", new Tools.ReadFileRequest("missing.md", null, null)).error())
        .isNotNull();
  }

  @Test
  void honorsDisabledSkillAndGlobalSetting() throws Exception {
    Tools tools = tools();
    Files.writeString(temporary.resolve("skills/review/SKILL.md"), """
        ---
        name: review
        description: Review
        enabled: false
        ---
        instructions
        """);
    repository.reload();
    assertThatThrownBy(() -> tools.readSkillResource("review", new Tools.ReadFileRequest("references/guide.md", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    properties.setEnabled(false);
    assertThatThrownBy(() -> tools.readSkillResource("review", new Tools.ReadFileRequest("references/guide.md", null, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void returnsContinuationForLongReferences() throws Exception {
    Tools tools = tools();
    Files.writeString(temporary.resolve("skills/review/references/guide.md"), "line\n".repeat(1200));
    var result = tools.readSkillResource("review", new Tools.ReadFileRequest("references/guide.md", null, null));
    assertThat(result.error()).isNull();
    assertThat(result.truncated()).isTrue();
    assertThat(result.nextRead()).isNotNull();
    var continuation = tools.readSkillResource("review", result.nextRead());
    assertThat(continuation.error()).isNull();
    assertThat(continuation.content()).isNotEmpty();
    assertThat(continuation.startLine()).isGreaterThan(result.startLine());
  }

  @Test
  void classifiesReferenceReadAsReadOnly() {
    assertThat(ToolPermissionPolicy.intrinsicCapabilities("readSkillResource"))
        .containsExactly(ActionCapability.READ);
  }

  @Test
  void exposesSkillResourceToolSchema() throws Exception {
    var callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
        .toolObjects(tools()).build().getToolCallbacks();
    var callback = java.util.Arrays.stream(callbacks)
        .filter(tool -> tool.getToolDefinition().name().equals("readSkillResource")).findFirst().orElseThrow();
    assertThat(callback.getToolDefinition().inputSchema()).contains("skillName", "file", "path", "offset");
  }

  @Test
  void doesNotAllowMcpToReadPersonalSkillResourcesOutsideItsProject() throws Exception {
    Tools tools = tools();
    Path project = Files.createDirectory(temporary.resolve("project"));
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "conversation", project, "project",
        dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP,
        dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY);
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      assertThat(tools.readSkillResource("review", new Tools.ReadFileRequest("references/guide.md", null, null)).error())
          .contains("MCP excludes");
    }
  }
}
