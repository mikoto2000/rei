package dev.mikoto2000.rei.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

/**
 * RootCommand
 */
@Component
@Command(
name = "",
description = "AI shell",
subcommands = {
  ChatCommand.class
})
@RequiredArgsConstructor
public class RootCommand {}
