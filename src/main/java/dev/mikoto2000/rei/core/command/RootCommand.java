package dev.mikoto2000.rei.core.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
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
  ModelsCommand.class,
  ModelCommand.class,
  ScheduleCommand.class,
  EmbedCommand.class,
  TaskCommand.class
},
mixinStandardHelpOptions = false)
@RequiredArgsConstructor
public class RootCommand {}
