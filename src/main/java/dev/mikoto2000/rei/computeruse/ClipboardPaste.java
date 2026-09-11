package dev.mikoto2000.rei.computeruse;

import java.awt.datatransfer.*;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes Rei clipboard transactions; restores only while our unique marker is still present. */
public final class ClipboardPaste {
  @FunctionalInterface public interface PasteGesture { void run() throws Exception; }
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static final DataFlavor MARKER = new DataFlavor("application/x-rei-clipboard-token;class=java.lang.String", "Rei clipboard token");
  private final Supplier<Clipboard> clipboard;
  private final Sleeper sleeper;
  private final long consumeDelay;
  public ClipboardPaste(Supplier<Clipboard> clipboard, Sleeper sleeper, long consumeDelay) {
    this.clipboard = clipboard; this.sleeper = sleeper; this.consumeDelay = consumeDelay;
  }
  public void paste(String text, PasteGesture gesture) throws Exception {
    LOCK.lockInterruptibly();
    try {
      Clipboard board = clipboard.get();
      Transferable original = snapshot(board.getContents(null));
      String token = UUID.randomUUID().toString();
      Transferable inserted = new Transferable() {
        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.stringFlavor, MARKER}; }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.stringFlavor) || flavor.equals(MARKER); }
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
          if (flavor.equals(MARKER)) return token;
          if (flavor.equals(DataFlavor.stringFlavor)) return text;
          throw new UnsupportedFlavorException(flavor);
        }
      };
      board.setContents(inserted, null);
      Exception failure = null;
      try {
        if (!owns(board, token)) throw new IllegalStateException("Clipboard changed before paste");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        gesture.run();
        sleeper.sleep(consumeDelay);
      } catch (Exception error) { failure = error; throw error; }
      finally {
        try {
          if (owns(board, token))
            board.setContents(original == null ? new StringSelection("") : original, null);
        } catch (Exception restoreError) {
          if (failure != null) failure.addSuppressed(restoreError);
          else throw restoreError;
        }
      }
    } finally { LOCK.unlock(); }
  }

  private static boolean owns(Clipboard board, String token) throws Exception {
    var current = board.getContents(null);
    return current != null && current.isDataFlavorSupported(MARKER) && token.equals(current.getTransferData(MARKER));
  }

  /** System Transferables may read lazily from native storage: materialize before replacing it. */
  private static Transferable snapshot(Transferable original) throws Exception {
    if (original == null) return null;
    var values = new java.util.LinkedHashMap<DataFlavor, Supplier<Object>>();
    for (DataFlavor flavor : original.getTransferDataFlavors()) {
      Object value = original.getTransferData(flavor);
      if (value instanceof java.io.InputStream stream) {
        byte[] bytes;
        try (stream) { bytes = stream.readNBytes(8 * 1024 * 1024 + 1); }
        if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("Clipboard stream too large to preserve");
        values.put(flavor, () -> new java.io.ByteArrayInputStream(bytes));
      } else if (value instanceof java.io.Reader reader) {
        var text = new StringBuilder();
        try (reader) {
          char[] buffer = new char[4096];
          int count;
          while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
            if (text.length() > 4 * 1024 * 1024) throw new IllegalStateException("Clipboard reader too large to preserve");
          }
        }
        String saved = text.toString();
        values.put(flavor, () -> new java.io.StringReader(saved));
      } else {
        values.put(flavor, () -> value);
      }
    }
    return new Transferable() {
      public DataFlavor[] getTransferDataFlavors() { return values.keySet().toArray(DataFlavor[]::new); }
      public boolean isDataFlavorSupported(DataFlavor flavor) { return values.containsKey(flavor); }
      public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!values.containsKey(flavor)) throw new UnsupportedFlavorException(flavor);
        return values.get(flavor).get();
      }
    };
  }
}
