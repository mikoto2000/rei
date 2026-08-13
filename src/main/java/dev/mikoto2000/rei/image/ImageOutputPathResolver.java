package dev.mikoto2000.rei.image;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ImageOutputPathResolver {

  private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final Path workDirectory;
  private final ImageProperties properties;
  private final Clock clock;

  public ImageOutputPathResolver(Path workDirectory, ImageProperties properties, Clock clock) {
    this.workDirectory = workDirectory;
    this.properties = properties;
    this.clock = clock;
  }

  public Path resolve(Path outputPath) {
    Path path = outputPath == null ? defaultOutputPath() : outputPath;
    if (!path.isAbsolute()) {
      path = workDirectory.resolve(path);
    }
    return path.toAbsolutePath().normalize();
  }

  private Path defaultOutputPath() {
    String fileName = "image-" + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + ".png";
    return properties.getOutputDirectory().resolve(fileName);
  }
}
