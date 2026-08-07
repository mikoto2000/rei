package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillPromptRenderer;
import dev.mikoto2000.rei.skills.AgentSkillSelection;
import dev.mikoto2000.rei.skills.AgentSkillSelectionService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class ChatCommandAgentSkillsTest {

  @Test
  void injectsExplicitSkillInstructionsIntoPrompt() {
    AgentSkill skill = skill("sample");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:sample hello")).thenReturn(
        new AgentSkillSelection(List.of(skill), List.of(), List.of(), "hello"));
    Prompt prompt = executeAndCapturePrompt("@skill:sample", "hello", selectionService);

    assertThat(prompt.getContents()).contains("## Skill: sample");
    assertThat(prompt.getContents()).contains("sample instructions");
    assertThat(prompt.getContents()).contains("--- User request ---\nhello");
    assertThat(prompt.getContents()).doesNotContain("@skill:sample");
  }

  @Test
  void injectsImplicitSkillInstructionsIntoPrompt() {
    AgentSkill skill = skill("implicit");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenReturn(new AgentSkillSelection(List.of(), List.of(skill), List.of(), "hello"));
    Prompt prompt = executeAndCapturePrompt("hello", selectionService);

    assertThat(prompt.getContents()).contains("## Skill: implicit");
    assertThat(prompt.getContents()).contains("implicit instructions");
  }

  @Test
  void keepsOriginalPromptWhenAgentSkillsAreUnavailable() {
    Prompt prompt = executeAndCapturePromptWithoutSkills("hello");

    assertThat(prompt.getContents()).isEqualTo("hello");
  }

  @Test
  void printsSkillWarnings() {
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:missing hello")).thenReturn(
        new AgentSkillSelection(List.of(), List.of(), List.of("[warn] Skill が見つかりません: missing"), "hello"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      executeAndCapturePrompt("@skill:missing", "hello", selectionService);
    } finally {
      System.setOut(originalOut);
    }

    assertThat(out.toString()).contains("[warn] Skill が見つかりません: missing");
  }

  @Test
  void printsSelectedSkillNames() {
    AgentSkill explicit = skill("explicit");
    AgentSkill implicit = skill("implicit");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenReturn(
        new AgentSkillSelection(List.of(explicit), List.of(implicit), List.of(), "hello"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      executeAndCapturePrompt("hello", selectionService);
    } finally {
      System.setOut(originalOut);
    }

    assertThat(out.toString()).contains("実行スキル: explicit, implicit");
  }

  private Prompt executeAndCapturePrompt(String prompt, AgentSkillSelectionService selectionService) {
    return executeAndCapturePrompt(new String[] { prompt }, selectionService);
  }

  private Prompt executeAndCapturePrompt(String first, String second, AgentSkillSelectionService selectionService) {
    return executeAndCapturePrompt(new String[] { first, second }, selectionService);
  }

  private Prompt executeAndCapturePrompt(String[] args, AgentSkillSelectionService selectionService) {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));

    ChatCommand command = new ChatCommand(chatClient, modelHolderService, new CommandCancellationService(),
        Mockito.mock(ChatResponseNarrator.class), Optional.empty(), Optional.of(selectionService),
        Optional.of(new AgentSkillPromptRenderer()));
    new CommandLine(command).execute(args);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    Mockito.verify(chatClient).prompt(promptCaptor.capture());
    return promptCaptor.getValue();
  }

  private Prompt executeAndCapturePromptWithoutSkills(String promptText) {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));

    ChatCommand command = new ChatCommand(chatClient, modelHolderService, new CommandCancellationService(),
        Mockito.mock(ChatResponseNarrator.class), Optional.empty());
    new CommandLine(command).execute(promptText);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    Mockito.verify(chatClient).prompt(promptCaptor.capture());
    return promptCaptor.getValue();
  }

  private AgentSkill skill(String name) {
    return new AgentSkill(name, name + " description", true, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
