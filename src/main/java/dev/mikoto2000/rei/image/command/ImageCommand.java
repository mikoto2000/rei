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
public class ImageCommand {
}
