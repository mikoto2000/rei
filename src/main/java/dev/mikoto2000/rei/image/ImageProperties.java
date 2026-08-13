package dev.mikoto2000.rei.image;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.image")
public class ImageProperties {

  private Path outputDirectory = Path.of(System.getProperty("user.dir"), ".rei", "images");
  private String size = "1024x1024";

  public Path getOutputDirectory() {
    return outputDirectory;
  }

  public void setOutputDirectory(Path outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }
}
