package dev.mikoto2000.rei.image;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 画像出力パスの指定を解決し、利用する対象を確定する。 */
public class ImageOutputPathResolver {

  /** ファイル記録日時。 */
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** 作業ディレクトリ。 */
  private final Path workDirectory;

  /** この機能の動作設定。 */
  private final ImageProperties properties;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ImageOutputPathResolverを初期化する。 */
  public ImageOutputPathResolver(Path workDirectory, ImageProperties properties, Clock clock) {
    this.workDirectory = workDirectory;
    this.properties = properties;
    this.clock = clock;
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public Path resolve(Path outputPath) {
    Path path = outputPath == null ? defaultOutputPath() : outputPath;
    if (!path.isAbsolute()) {
      var execution = dev.mikoto2000.rei.core.execution.ExecutionScope.current();
      path = (execution == null ? workDirectory : execution.projectRoot()).resolve(path);
    }
    return path.toAbsolutePath().normalize();
  }

  /** 画像の生成で用いる既定出力パスを返す。 */
  private Path defaultOutputPath() {
    var execution = dev.mikoto2000.rei.core.execution.ExecutionScope.current();
    String suffix = execution == null ? "" : "-" + execution.id();
    String fileName = "image-" + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + suffix + ".png";
    return properties.getOutputDirectory().resolve(fileName);
  }
}
