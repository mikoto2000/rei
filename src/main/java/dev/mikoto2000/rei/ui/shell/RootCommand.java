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
  ChatCommand.class,
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
  SkillCommand.class,
  ImageCommand.class,
  SummarizeCommand.class,
  ProfileCommand.class
},
mixinStandardHelpOptions = false)
@RequiredArgsConstructor
public class RootCommand {}
