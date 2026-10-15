package dev.mikoto2000.rei.application.cli;

import java.util.List;

/** Immutable grammar data; it contains no executor, service, or filesystem capability. */
public record CliCatalog(List<Command> commands) {
  public CliCatalog {
    commands = List.copyOf(commands);
  }

  public record Command(
      String name,
      List<String> aliases,
      String description,
      List<Option> options,
      List<Parameter> parameters,
      List<Command> commands,
      boolean hasDefaultAction,
      List<Group> groups) {
    public Command(
        String name,
        List<String> aliases,
        String description,
        List<Option> options,
        List<Parameter> parameters,
        List<Command> commands,
        boolean hasDefaultAction) {
      this(name, aliases, description, options, parameters, commands, hasDefaultAction, List.of());
    }

    public Command(
        String name,
        List<String> aliases,
        String description,
        List<Option> options,
        List<Parameter> parameters,
        List<Command> commands) {
      this(name, aliases, description, options, parameters, commands, true);
    }

    public Command {
      aliases = List.copyOf(aliases);
      options = List.copyOf(options);
      parameters = List.copyOf(parameters);
      commands = List.copyOf(commands);
      groups = groups == null ? List.of() : List.copyOf(groups);
    }
  }

  public record Group(
      boolean exclusive, String multiplicity, List<Option> options, List<Group> groups) {
    public Group {
      options = List.copyOf(options);
      groups = List.copyOf(groups);
    }
  }

  public record Option(
      List<String> names,
      String description,
      String defaultValue,
      String arity,
      boolean required,
      List<String> choices,
      String candidateType,
      String label,
      boolean multiValue,
      String split) {
    public Option(
        List<String> names,
        String description,
        String defaultValue,
        String arity,
        boolean required,
        List<String> choices,
        String candidateType) {
      this(
          names, description, defaultValue, arity, required, choices, candidateType, "", false, "");
    }

    public Option(
        List<String> names,
        String description,
        String defaultValue,
        String arity,
        boolean required,
        List<String> choices) {
      this(names, description, defaultValue, arity, required, choices, "");
    }

    public Option {
      names = List.copyOf(names);
      choices = List.copyOf(choices);
      candidateType = candidateType == null ? "" : candidateType;
      label = label == null ? "" : label;
      split = split == null ? "" : split;
    }
  }

  public record Parameter(
      String index,
      String label,
      String arity,
      String description,
      List<String> choices,
      String candidateType,
      boolean multiValue) {
    public Parameter(
        String index,
        String label,
        String arity,
        String description,
        List<String> choices,
        String candidateType) {
      this(index, label, arity, description, choices, candidateType, false);
    }

    public Parameter {
      choices = List.copyOf(choices);
      candidateType = candidateType == null ? "" : candidateType;
    }
  }
}
