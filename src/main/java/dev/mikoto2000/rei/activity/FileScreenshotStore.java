package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ファイル画面画像の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Flat generated filenames only; retention never follows links or touches journal tables.
 */
public final class FileScreenshotStore implements ScreenshotStore {
  /** 作業ルート。 */
  private final Path root;

  /** 指定された値や利用先を使って、FileScreenshotStoreを初期化する。 */
  public FileScreenshotStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public List<String> save(String id, Instant capturedAt, CapturedScreen screen) throws Exception {
    UUID.fromString(id);
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root))
      throw new IllegalStateException("Screenshot directory must not be a link");
    var references = new ArrayList<String>();
    try {
      int index = 0;
      for (var display : screen.displays()) {
        String name = capturedAt.toEpochMilli() + "_" + id + "_" + (index++) + ".png";
        references.add(name);
        try (var stream =
            Files.newOutputStream(root.resolve(name), StandardOpenOption.CREATE_NEW)) {
          PngScreenshotEncoder.write(display.image(), stream);
        }
      }
      return List.copyOf(references);
    } catch (Exception e) {
      for (var name : references) Files.deleteIfExists(root.resolve(name));
      throw e;
    }
  }

  /** 作業状況の把握に関する後片付けを処理する。 */
  public void cleanup(Instant before) throws Exception {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.newDirectoryStream(root, "*.png")) {
      for (var path : files) {
        String name = path.getFileName().toString();
        if (name.matches("[0-9]+_[0-9a-f-]{36}_[0-9]+\\.png")
            && !Files.isSymbolicLink(path)
            && Long.parseLong(name.substring(0, name.indexOf('_'))) < before.toEpochMilli())
          Files.deleteIfExists(path);
      }
    }
  }
}
