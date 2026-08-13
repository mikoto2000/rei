package dev.mikoto2000.rei.image;

import java.nio.file.Path;

public record ImageGenerationRequest(
    String prompt,
    Path outputPath,
    String model,
    ImageSize size) {
}
