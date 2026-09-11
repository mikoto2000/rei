package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.datatransfer.*;
import org.junit.jupiter.api.Test;

class ClipboardPasteTest {
  @Test void unicodeIsAvailableDuringPasteAndOriginalTransferableIsRestored() throws Exception {
    var clipboard = new Clipboard("test");
    var original = new StringSelection("original");
    clipboard.setContents(original, null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    paste.paste("日本語😀", () -> assertEquals("日本語😀", clipboard.getData(DataFlavor.stringFlavor)));
    assertEquals("original", clipboard.getData(DataFlavor.stringFlavor));
  }
  @Test void restoresOnDispatchFailureWithoutOverwritingConcurrentChanges() throws Exception {
    var clipboard = new Clipboard("test");
    var original = new StringSelection("original");
    clipboard.setContents(original,null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    assertThrows(IllegalStateException.class, () -> paste.paste("new", () -> { throw new IllegalStateException(); }));
    assertEquals("original",clipboard.getData(DataFlavor.stringFlavor));
    var external = new StringSelection("external");
    paste.paste("new", () -> clipboard.setContents(external,null));
    assertSame(external,clipboard.getContents(null));
  }
  @Test void acquisitionFailureNeverDispatches() {
    var paste = new ClipboardPaste(() -> { throw new IllegalStateException("busy"); }, ms -> {}, 10);
    assertThrows(IllegalStateException.class, () -> paste.paste("new", () -> fail()));
  }

  @Test void snapshotsLazyNativeContentsBeforeReplacingClipboard() throws Exception {
    var clipboard = new Clipboard("test");
    var stillAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);
    var original = new Transferable() {
      public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.stringFlavor}; }
      public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.stringFlavor.equals(flavor); }
      public Object getTransferData(DataFlavor flavor) {
        if (!stillAvailable.get()) throw new IllegalStateException("Native clipboard contents were replaced");
        return "before";
      }
    };
    clipboard.setContents(original,null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    paste.paste("after", () -> stillAvailable.set(false));
    assertEquals("before",clipboard.getData(DataFlavor.stringFlavor));
  }

  @Test void detectsConcurrentReplacementBeforePasteAndReportsRestoreFailure() throws Exception {
    var external = new StringSelection("external");
    var clipboard = new Clipboard("test") {
      int writes;
      @Override public synchronized void setContents(Transferable value, ClipboardOwner owner) {
        super.setContents(++writes == 2 ? external : value, owner);
      }
    };
    clipboard.setContents(new StringSelection("before"),null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    assertThrows(IllegalStateException.class, () -> paste.paste("after", () -> fail("Must not paste someone else's clipboard")));
    assertSame(external,clipboard.getContents(null));
    var broken = new Clipboard("test") {
      int writes;
      @Override public synchronized void setContents(Transferable value, ClipboardOwner owner) {
        if (++writes == 3) throw new IllegalStateException("Restore failed");
        super.setContents(value,owner);
      }
    };
    broken.setContents(new StringSelection("before"),null);
    assertThrows(IllegalStateException.class, () -> new ClipboardPaste(() -> broken, ms -> {}, 10).paste("after", () -> {}));
  }
}
