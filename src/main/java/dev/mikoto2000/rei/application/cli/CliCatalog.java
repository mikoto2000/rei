package dev.mikoto2000.rei.application.cli;

import java.util.List;

/**
 * コマンドの案内で受け渡す軽量CLI利用可能な項目を表す。
 *
 * <p>Immutable grammar data; it contains no executor, service, or filesystem capability.
 *
 * @param commands コマンド一覧
 */
public record CliCatalog(List<Command> commands) {
  /** 受け取った情報からCliCatalogを作成する。 */
  public CliCatalog {
    commands = List.copyOf(commands);
  }

  /**
   * コマンドの案内で受け渡すコマンドを表す。
   *
   * @param name 名前
   * @param aliases 別名一覧
   * @param description 説明
   * @param options 選択可能な設定
   * @param parameters 引数
   * @param commands コマンド一覧
   * @param hasDefaultAction 保有既定操作
   * @param groups グループ一覧
   */
  public record Command(
      String name,
      List<String> aliases,
      String description,
      List<Option> options,
      List<Parameter> parameters,
      List<Command> commands,
      boolean hasDefaultAction,
      List<Group> groups) {
    /** コマンドの案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
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

    /** コマンドの案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Command(
        String name,
        List<String> aliases,
        String description,
        List<Option> options,
        List<Parameter> parameters,
        List<Command> commands) {
      this(name, aliases, description, options, parameters, commands, true);
    }

    /** 受け取った情報からCommandを作成する。 */
    public Command {
      aliases = List.copyOf(aliases);
      options = List.copyOf(options);
      parameters = List.copyOf(parameters);
      commands = List.copyOf(commands);
      groups = groups == null ? List.of() : List.copyOf(groups);
    }
  }

  /**
   * コマンドの案内で受け渡すグループを表す。
   *
   * @param exclusive 排他
   * @param multiplicity 重複の度合い
   * @param options 選択可能な設定
   * @param groups グループ一覧
   */
  public record Group(
      boolean exclusive, String multiplicity, List<Option> options, List<Group> groups) {
    /** 受け取った情報からGroupを作成する。 */
    public Group {
      options = List.copyOf(options);
      groups = List.copyOf(groups);
    }
  }

  /**
   * コマンドの案内で受け渡す選択項目を表す。
   *
   * @param names 名前一覧
   * @param description 説明
   * @param defaultValue 既定値
   * @param arity 引数の数
   * @param required 必須
   * @param choices 選択肢
   * @param candidateType 候補種別
   * @param label 表示名
   * @param multiValue 複数値
   * @param split 分割
   */
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
    /** コマンドの案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
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

    /** コマンドの案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Option(
        List<String> names,
        String description,
        String defaultValue,
        String arity,
        boolean required,
        List<String> choices) {
      this(names, description, defaultValue, arity, required, choices, "");
    }

    /** 受け取った情報からOptionを作成する。 */
    public Option {
      names = List.copyOf(names);
      choices = List.copyOf(choices);
      candidateType = candidateType == null ? "" : candidateType;
      label = label == null ? "" : label;
      split = split == null ? "" : split;
    }
  }

  /**
   * コマンドの案内で受け渡す引数を表す。
   *
   * @param index 位置
   * @param label 表示名
   * @param arity 引数の数
   * @param description 説明
   * @param choices 選択肢
   * @param candidateType 候補種別
   * @param multiValue 複数値
   */
  public record Parameter(
      String index,
      String label,
      String arity,
      String description,
      List<String> choices,
      String candidateType,
      boolean multiValue) {
    /** コマンドの案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Parameter(
        String index,
        String label,
        String arity,
        String description,
        List<String> choices,
        String candidateType) {
      this(index, label, arity, description, choices, candidateType, false);
    }

    /** 受け取った情報からParameterを作成する。 */
    public Parameter {
      choices = List.copyOf(choices);
      candidateType = candidateType == null ? "" : candidateType;
    }
  }
}
