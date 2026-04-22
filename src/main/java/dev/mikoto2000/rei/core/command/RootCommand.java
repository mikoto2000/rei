package dev.mikoto2000.rei.core.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.briefing.command.BriefingCommand;
import dev.mikoto2000.rei.feed.command.FeedCommand;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.interest.command.InterestCommand;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
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
  ConfigCommand.class,
  ScheduleCommand.class,
  EmbedCommand.class,
  TaskCommand.class,
  FeedCommand.class,
  BriefingCommand.class,
  ReminderCommand.class,
  InterestCommand.class
},
mixinStandardHelpOptions = false)
@RequiredArgsConstructor
public class RootCommand {}
