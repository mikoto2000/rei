package dev.mikoto2000.rei.image.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

@Component
@Command(
    name = "image",
    description = "画像生成を行います。",
    subcommands = {
        GenerateCommand.class
    },
    mixinStandardHelpOptions = true)
@RequiredArgsConstructor
public class ImageCommand implements java.util.concurrent.Callable<Integer> {
  @picocli.CommandLine.Parameters(arity = "0..*", description = "画像生成プロンプト")
  private java.util.List<String> promptParts;
  @picocli.CommandLine.Spec
  private picocli.CommandLine.Model.CommandSpec spec;

  @Override
  public Integer call() {
    if (promptParts == null || promptParts.isEmpty()) {
      spec.commandLine().usage(spec.commandLine().getErr());
      return 2;
    }
    return spec.commandLine().getSubcommands().get("generate")
        .execute("--", String.join(" ", promptParts));
  }
}
