package dev.mikoto2000.rei.image.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** 画像生成を行いますを行うための利用者コマンド。 */
@Component
@Command(
    name = "image",
    description = "画像生成を行います。",
    subcommands = {GenerateCommand.class},
    mixinStandardHelpOptions = true)
@RequiredArgsConstructor
public class ImageCommand implements java.util.concurrent.Callable<Integer> {
  /** プロンプト部分一覧。 */
  @picocli.CommandLine.Parameters(arity = "0..*", description = "画像生成プロンプト")
  private java.util.List<String> promptParts;

  /** 仕様。 */
  @picocli.CommandLine.Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** 画像生成を行いますを行う。 */
  @Override
  public Integer call() {
    if (promptParts == null || promptParts.isEmpty()) {
      spec.commandLine().usage(spec.commandLine().getErr());
      return 2;
    }
    return spec.commandLine()
        .getSubcommands()
        .get("generate")
        .execute("--", String.join(" ", promptParts));
  }
}
