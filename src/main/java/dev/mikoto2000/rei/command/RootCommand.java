package dev.mikoto2000.rei.command;

import org.springframework.stereotype.Component;

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
  ModelCommand.class
},
mixinStandardHelpOptions = true)
@RequiredArgsConstructor
public class RootCommand {}
