package dev.mikoto2000.rei.terminal;

import java.nio.file.Path;
import java.util.*;

/**
 * 軽量CLIの起動で受け渡す起動オプションを表す。
 *
 * @param mode 動作モード
 * @param action 操作
 * @param confirmed 確認済み
 * @param help 使い方
 * @param noHistory 入力履歴を保存しない指定
 * @param backendJar バックエンドのJARファイル
 * @param project プロジェクト
 * @param backendArguments 従来シェルへ渡す引数
 */
public record LauncherOptions(
    String mode,
    String action,
    boolean confirmed,
    boolean help,
    boolean noHistory,
    Path backendJar,
    Path project,
    List<String> backendArguments) {
  /** 起動モードと操作を解釈し、組み合わせが成立する指定だけを受け付ける。 */
  public static LauncherOptions parse(String[] args) {
    String mode = "auto", action = null;
    boolean specified = false, yes = false, help = false, noHistory = false;
    Path jar = Path.of("target/rei-0.0.1-SNAPSHOT.jar"), project = null;
    var backend = new ArrayList<String>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--mode") || arg.startsWith("--mode=")) {
        if (specified) throw new IllegalArgumentException("Specify --mode only once");
        specified = true;
        mode = arg.equals("--mode") ? value(args, ++i) : arg.substring(7);
      } else
        switch (arg) {
          case "server" -> {
            if (specified) throw new IllegalArgumentException("Duplicate mode");
            specified = true;
            mode = "server";
          }
          case "status", "stop" -> {
            if (action != null) throw new IllegalArgumentException("Duplicate server action");
            action = arg;
          }
          case "--yes" -> yes = true;
          case "--help", "-h" -> help = true;
          case "--no-history" -> noHistory = true;
          case "--backend-jar" -> jar = Path.of(value(args, ++i));
          case "--project" -> project = Path.of(value(args, ++i)).toAbsolutePath().normalize();
          case "--" -> {
            backend.addAll(Arrays.asList(args).subList(i + 1, args.length));
            i = args.length;
          }
          default -> throw new IllegalArgumentException("Unknown launcher option: " + arg);
        }
    }
    // 起動モードごとに受け付ける操作を制限し、無効な組み合わせで起動しない。
    if (!Set.of("auto", "client", "server", "legacy-shell").contains(mode))
      throw new IllegalArgumentException("Available modes: auto, client, server, legacy-shell");
    if (action != null && !mode.equals("server"))
      throw new IllegalArgumentException("Server action requires server mode");
    if (!backend.isEmpty() && !mode.equals("legacy-shell"))
      throw new IllegalArgumentException(
          "Backend arguments are supported only in legacy-shell mode");
    return new LauncherOptions(
        mode,
        action,
        yes,
        help,
        noHistory,
        jar.toAbsolutePath().normalize(),
        project,
        List.copyOf(backend));
  }

  /** 値を伴う起動オプションから指定を受け取り、値がない場合は入力の問題を伝える。 */
  private static String value(String[] args, int i) {
    if (i >= args.length) throw new IllegalArgumentException("Missing option value");
    return args[i];
  }
}
