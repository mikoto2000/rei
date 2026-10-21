package dev.mikoto2000.rei.application.cli;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgGroupSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * コマンドの案内に必要な軽量CLI入力文法を扱う。
 *
 * <p>Data-only help model. It has no command object, executor, service, or filesystem access.
 */
public final class CliGrammar {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private CliGrammar() {}

  /** コマンドの案内で用いる説明を返す。 */
  public static CommandSpec describe(CliCatalog.Command command) {
    var spec =
        CommandSpec.create().name(command.name()).aliases(command.aliases().toArray(String[]::new));
    if (!command.description().isEmpty()) spec.usageMessage().description(command.description());
    var grouped = new java.util.HashSet<CliCatalog.Option>();
    for (var group : command.groups()) collect(group, grouped);
    for (var option : command.options())
      if (!grouped.contains(option)) spec.addOption(option(option));
    for (var group : command.groups()) spec.addArgGroup(group(group));
    for (var parameter : command.parameters()) {
      var builder =
          PositionalParamSpec.builder()
              .arity(parameter.arity().isEmpty() ? "1" : parameter.arity())
              .type(parameter.multiValue() ? String[].class : String.class)
              .description(parameter.description());
      builder.index(
          parameter.index().isEmpty()
              ? (parameter.multiValue() ? "0..*" : "0")
              : parameter.index());
      if (!parameter.label().isEmpty()) builder.paramLabel(parameter.label());
      spec.addPositional(builder.build());
    }
    for (var child : command.commands())
      spec.addSubcommand(child.name(), new CommandLine(describe(child)));
    return spec;
  }

  /** コマンドの案内で用いる選択項目を返す。 */
  private static OptionSpec option(CliCatalog.Option option) {
    var builder =
        OptionSpec.builder(option.names().toArray(String[]::new))
            .description(option.description())
            .arity(option.arity())
            .required(option.required())
            .type(
                option.arity().equals("0")
                    ? boolean.class
                    : option.multiValue() ? String[].class : String.class);
    if (!option.split().isEmpty()) builder.splitRegex(option.split());
    if (option.defaultValue() != null) builder.defaultValue(option.defaultValue());
    if (!option.label().isEmpty()) builder.paramLabel(option.label());
    return builder.build();
  }

  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  private static void collect(CliCatalog.Group group, java.util.Set<CliCatalog.Option> values) {
    values.addAll(group.options());
    group.groups().forEach(child -> collect(child, values));
  }

  /** コマンドの案内で用いるグループを返す。 */
  private static ArgGroupSpec group(CliCatalog.Group group) {
    var builder =
        ArgGroupSpec.builder().exclusive(group.exclusive()).multiplicity(group.multiplicity());
    group.options().forEach(option -> builder.addArg(option(option)));
    group.groups().forEach(child -> builder.addSubgroup(group(child)));
    return builder.build();
  }
}
