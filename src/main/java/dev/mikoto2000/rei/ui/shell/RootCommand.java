package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.bluesky.command.BskyCommand;
import dev.mikoto2000.rei.briefing.command.BriefingCommand;
import dev.mikoto2000.rei.core.command.ConfigCommand;
import dev.mikoto2000.rei.core.command.EmbedCommand;
import dev.mikoto2000.rei.core.command.ModelCommand;
import dev.mikoto2000.rei.core.command.ModelsCommand;
import dev.mikoto2000.rei.core.command.ProfileCommand;
import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.command.SearchCommand;
import dev.mikoto2000.rei.core.command.ShCommand;
import dev.mikoto2000.rei.feed.command.FeedCommand;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.image.command.ImageCommand;
import dev.mikoto2000.rei.interest.command.InterestCommand;
import dev.mikoto2000.rei.memory.command.MemoryCommand;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
import dev.mikoto2000.rei.skills.command.SkillCommand;
import dev.mikoto2000.rei.summarize.command.SummarizeCommand;
import dev.mikoto2000.rei.task.command.TaskCommand;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

/**
 * RootCommand
 */
@Component
@Command(
version = "v1.0.0",
name = "",
description = "AI shell",
subcommands = {
  dev.mikoto2000.rei.llm.capture.LlmCaptureCommand.class,
  dev.mikoto2000.rei.core.command.DocumentCommand.class,
  dev.mikoto2000.rei.core.command.RepairCommand.class,
  dev.mikoto2000.rei.reflection.ReflectionCommand.class,
  dev.mikoto2000.rei.goal.GoalCommand.class,
  dev.mikoto2000.rei.attention.AttentionCommand.class,
  dev.mikoto2000.rei.temporal.TimerCommand.class,
  dev.mikoto2000.rei.core.dependency.DependencyCommand.class,
  dev.mikoto2000.rei.core.policy.ApprovalCommand.class,
  dev.mikoto2000.rei.checkpoint.CheckpointCommand.class,
  dev.mikoto2000.rei.checkpoint.ResumeCommand.class,
  dev.mikoto2000.rei.workcontext.WorkCommand.class,
  dev.mikoto2000.rei.paper.PaperCommand.class,
  dev.mikoto2000.rei.activity.ActivityCommand.class,
  dev.mikoto2000.rei.externalagent.ExternalAgentCommand.class,
  dev.mikoto2000.rei.externalagent.BeginnerReviewCommand.class,
  dev.mikoto2000.rei.subagent.SubAgentCommand.class,
  dev.mikoto2000.rei.voice.VoiceCommand.class,
  ChatCommand.class,
  SessionCommand.class,
  ConversationModeCommand.class,
  CancelCommand.class,
  RunsCommand.class,
  HistoryCommand.class,
  SearchCommand.class,
  ModelsCommand.class,
  ModelCommand.class,
  ShCommand.class,
  ProjectCommand.class,
  ConfigCommand.class,
  ScheduleCommand.class,
  EmbedCommand.class,
  TaskCommand.class,
  FeedCommand.class,
  BriefingCommand.class,
  ReminderCommand.class,
  BskyCommand.class,
  InterestCommand.class,
  MemoryCommand.class,
  dev.mikoto2000.rei.memory.command.SleepCommand.class,
  SkillCommand.class,
  ImageCommand.class,
  SummarizeCommand.class,
  ProfileCommand.class
},
mixinStandardHelpOptions = false)
@RequiredArgsConstructor
public class RootCommand {
  @org.springframework.beans.factory.annotation.Value("${rei.embedding.enabled:true}")
  private boolean embeddingEnabled = true;

  @org.springframework.beans.factory.annotation.Value("${rei.material-review.beginner.enabled:true}")
  private boolean beginnerReviewEnabled = true;

  public void configureCommands(picocli.CommandLine command) {
    if (!beginnerReviewEnabled) command.getCommandSpec().removeSubcommand("material-review");
    if (!embeddingEnabled) {
      command.getCommandSpec().removeSubcommand("embed");
    }
  }
}
