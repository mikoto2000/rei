package dev.mikoto2000.rei.activity;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import dev.mikoto2000.rei.computeruse.CapturedScreen;

/** Flat generated filenames only; retention never follows links or touches journal tables. */
public final class FileScreenshotStore implements ScreenshotStore {
  private final Path root;
  public FileScreenshotStore(Path root) { this.root=root.toAbsolutePath().normalize(); }
  public List<String> save(String id,Instant capturedAt,CapturedScreen screen) throws Exception {
    UUID.fromString(id); Files.createDirectories(root);
    if(Files.isSymbolicLink(root)) throw new IllegalStateException("Screenshot directory must not be a link");
    var references=new ArrayList<String>();
    try {
      int index=0;
      for(var display:screen.displays()) {
        String name=capturedAt.toEpochMilli()+"_"+id+"_"+(index++)+".png";
        references.add(name);
        try(var stream=Files.newOutputStream(root.resolve(name),StandardOpenOption.CREATE_NEW)) {
          PngScreenshotEncoder.write(display.image(), stream);
        }
      }
      return List.copyOf(references);
    } catch(Exception e) { for(var name:references)Files.deleteIfExists(root.resolve(name));throw e; }
  }
  public void cleanup(Instant before) throws Exception {
    if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
    try(var files=Files.newDirectoryStream(root,"*.png")) {
      for(var path:files) {
        String name=path.getFileName().toString();
        if(name.matches("[0-9]+_[0-9a-f-]{36}_[0-9]+\\.png") && !Files.isSymbolicLink(path)
            && Long.parseLong(name.substring(0,name.indexOf('_')))<before.toEpochMilli()) Files.deleteIfExists(path);
      }
    }
  }
}
