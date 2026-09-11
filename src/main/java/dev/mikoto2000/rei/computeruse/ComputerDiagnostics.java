package dev.mikoto2000.rei.computeruse;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Opt-in local artifacts. Filenames never derive from model text or OS display identifiers. */
public final class ComputerDiagnostics {
  private final Path root;
  private final ObjectMapper json = new ObjectMapper();
  public ComputerDiagnostics(Path root) { this.root = root == null ? null : root.toAbsolutePath().normalize(); }
  public Path begin() throws IOException {
    if (root == null) return null;
    return Files.createDirectories(root.resolve(java.time.Instant.now().toString().replace(':','-') + "-" + UUID.randomUUID()));
  }
  private Path step(Path run, int step) throws IOException {
    return Files.createDirectories(run.resolve("step-%03d".formatted(step)));
  }
  public void observed(Path run, int number, CapturedScreen screen) throws IOException {
    if (run == null) return;
    var directory = step(run,number);
    var metadata = new ArrayList<Map<String,Object>>();
    int index = 0;
    for (var display : screen.displays()) {
      var name = "display-" + (++index) + ".png";
      ImageIO.write(display.image(),"png",directory.resolve(name).toFile());
      var item = geometry(display);
      item.put("image", name);
      metadata.add(item);
    }
    json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("displays.json").toFile(),metadata);
  }
  private Map<String,Object> geometry(DisplayCapture display) {
    var g = display.geometry(); var b = g.bounds();
    Map<String,Object> item = new LinkedHashMap<>();
    item.put("displayId",g.id()); item.put("primary",g.primary());
    item.put("bounds",Map.of("x",b.x,"y",b.y,"width",b.width,"height",b.height));
    item.put("scaleX",g.scaleX()); item.put("scaleY",g.scaleY());
    item.put("imageWidth",display.image().getWidth()); item.put("imageHeight",display.image().getHeight());
    return item;
  }
  public void action(Path run, int number, CapturedScreen screen, ComputerAction action, String phase) throws IOException {
    if (run == null) return;
    if (!Set.of("decided","dispatched").contains(phase)) throw new IllegalArgumentException("Invalid diagnostic phase");
    var directory = step(run,number);
    var target = action instanceof ComputerAction.Click a ? a.target()
        : action instanceof ComputerAction.DoubleClick a ? a.target() : null;
    Map<String,Object> data = new LinkedHashMap<>();
    data.put("action",action.getClass().getSimpleName()); data.put("phase",phase);
    data.put("risk",action.risk().name());
    if (target != null) {
      var display = screen.display(target.displayId());
      data.putAll(geometry(display));
      var point = display.desktopPoint(target);
      data.put("imageX",target.x()); data.put("imageY",target.y());
      data.put("normalizedX",target.normalizedX()); data.put("normalizedY",target.normalizedY());
      data.put("robotX",point.x); data.put("robotY",point.y);
      var source = display.image();
      var overlay = new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
      var g = overlay.createGraphics();
      try {
        g.drawImage(source,0,0,null); g.setColor(Color.RED); g.setStroke(new BasicStroke(3));
        g.drawOval(target.x()-15,target.y()-15,30,30);
        g.drawLine(target.x()-22,target.y(),target.x()+22,target.y());
        g.drawLine(target.x(),target.y()-22,target.x(),target.y()+22);
      } finally { g.dispose(); }
      ImageIO.write(overlay,"png",directory.resolve("target.png").toFile());
    }
    json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(phase + ".json").toFile(),data);
  }
}
