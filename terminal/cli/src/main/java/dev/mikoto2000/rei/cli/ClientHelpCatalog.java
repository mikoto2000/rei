package dev.mikoto2000.rei.cli;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import dev.mikoto2000.rei.application.cli.CliGrammar;
import dev.mikoto2000.rei.application.cli.CommandHelp;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/** Backend の定義にクライアント固有の使い方を加えた、実行能力のないヘルプモデル。 */
final class ClientHelpCatalog {
  private ClientHelpCatalog() {}

  static CommandLine create(CliCatalog catalog) {
    var root = new CommandLine(spec("", "れいのコマンド一覧。各コマンドに --help を付けると詳しい使い方を表示します。"));
    for (var command : catalog.commands())
      root.addSubcommand(command.name(), CliGrammar.describe(command));
    put(root, positional("help", "コマンドの役割と使い方を表示します。", "COMMAND", "0..*"));
    put(root, spec("exit", "このクライアントを終了します。Backend の処理は継続します。").aliases("quit"));
    put(root, spec("paste", "複数行のメッセージを入力します。単独の . の行で入力を終了し、送信します。"));
    put(root, spec("sh", "選択中の Project でローカルのシェルを開きます。シェルを終了するとれいに戻ります。"));
    put(root, positional("watch", "指定した Run のイベントを表示します。Ctrl+C で表示を切り離します。", "RUN_ID", "1"));
    put(
        root,
        positional(
            "runs", "全 Project の実行中処理を一覧表示します。ID を指定するとその Run の詳細を表示します。", "RUN_ID", "0..1"));
    put(
        root,
        positional("cancel", "指定した Run を停止します。ID を省略すると選択中 Project の処理を停止します。", "RUN_ID", "0..1"));
    put(root, positional("receipt", "送信時の受付キーから、依頼が受け付けられたかを確認します。", "RECEIPT_KEY", "1"));
    put(
        root,
        positional(
            "run-mode",
            "会話の実行モードを設定します。exclusive は通常実行、read-only は読取り専用、conversation は会話用です。",
            "exclusive|read-only|conversation",
            "1"));
    var chat =
        positional(
            "chat", "メッセージを送信します。--run は実行中 Run への追加指示です。-- 以降はメッセージとして扱います。", "MESSAGE", "1..*");
    chat.addOption(
        OptionSpec.builder("--run")
            .type(String.class)
            .paramLabel("RUN_ID")
            .description("追加指示の対象 Run。exclusive モードで使用します。")
            .build());
    chat.addOption(
        OptionSpec.builder("--mode")
            .type(String.class)
            .paramLabel("MODE")
            .description("exclusive / read-only / conversation。省略時は /run-mode の設定を使用します。")
            .build());
    put(root, chat);
    var project = parent(root, "project", "作業対象の Project を管理します。");
    put(project, spec("list", "登録済み Project を一覧表示します。"));
    put(project, positional("select", "登録済み Project を ID で選択します。", "PROJECT_ID", "1"));
    put(project, positional("register", "ローカルディレクトリを登録し、その Project を選択します。", "LOCAL_PATH", "1"));
    put(project, positional("add", "ローカルディレクトリを Project に登録します。", "DIR", "1"));
    put(project, positional("cd", "ディレクトリを指定して Project を切り替えます。未登録なら登録します。", "DIR", "1"));
    put(project, positional("remove", "ディレクトリを指定して Project の登録を解除します。", "DIR", "1"));
    var session = parent(root, "session", "選択中 Project の会話 Session を管理します。");
    put(session, spec("list", "選択中 Project の Session を一覧表示します。"));
    put(session, spec("show", "現在の Session の情報を表示します。"));
    put(session, spec("end", "現在の Session の利用を終了します。履歴は再開できます。"));
    put(session, positional("new", "新しい Session を作成します。タイトルは省略できます。", "TITLE", "0..1"));
    put(session, positional("select", "選択中 Project の Session を ID で選択します。", "SESSION_ID", "1"));
    put(
        session,
        positional("switch", "選択中 Project の Session を ID で再開します。", "SESSION_ID", "1")
            .aliases("resume"));
    var history = parent(root, "history", "保存済み会話の履歴を表示・検索します。");
    var inputHistory = parent(root, "input-history", "このクライアントの入力履歴の保存を切り替えます。");
    for (var parent : java.util.List.of(history, inputHistory)) {
      put(parent, spec("off", "入力履歴の保存を無効にします。"));
      put(parent, spec("on", "入力履歴の保存を有効にします。"));
    }
    var resume = root.getSubcommands().get("resume");
    if (resume != null) put(resume, spec("overview", "選択中 Project の中断された作業の再開案内を表示します。"));
    return CommandHelp.install(root);
  }

  private static CommandSpec spec(String name, String description) {
    var spec = CommandSpec.create().name(name);
    spec.usageMessage().description(description);
    return spec;
  }

  private static CommandSpec positional(
      String name, String description, String label, String arity) {
    return spec(name, description)
        .addPositional(
            PositionalParamSpec.builder()
                .index("0")
                .arity(arity)
                .paramLabel(label)
                .type(arity.endsWith("*") ? String[].class : String.class)
                .build());
  }

  private static void put(CommandLine parent, CommandSpec child) {
    var old = parent.getSubcommands().get(child.name());
    if (old != null) {
      for (var option : old.getCommandSpec().options())
        if (java.util.Arrays.stream(option.names()).noneMatch(child.optionsMap()::containsKey))
          child.addOption(option.toBuilder().build());
      parent.getCommandSpec().removeSubcommand(old.getCommandName());
    }
    parent.addSubcommand(child.name(), child);
  }

  private static CommandLine parent(CommandLine root, String name, String description) {
    if (!root.getSubcommands().containsKey(name)) put(root, spec(name, description));
    return root.getSubcommands().get(name);
  }
}
