package dev.mikoto2000.rei.computeruse;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 画面操作の支援に必要なクリップボードPasteを扱う。
 *
 * <p>Serializes Rei clipboard transactions; restores only while our unique marker is still present.
 */
public final class ClipboardPaste {
  /** 画面操作の支援に必要なPasteGestureを扱う。 */
  @FunctionalInterface
  public interface PasteGesture {
    /** 画面操作の支援の操作を実行する。 */
    void run() throws Exception;
  }

  /** 排他制御。 */
  private static final ReentrantLock LOCK = new ReentrantLock();

  /** 目印。 */
  private static final DataFlavor MARKER =
      new DataFlavor(
          "application/x-rei-clipboard-token;class=java.lang.String", "Rei clipboard token");

  /** クリップボード。 */
  private final Supplier<Clipboard> clipboard;

  /** 待機の実行先。 */
  private final Sleeper sleeper;

  /** consume遅延時間。 */
  private final long consumeDelay;

  /** 指定された値や利用先を使って、ClipboardPasteを初期化する。 */
  public ClipboardPaste(Supplier<Clipboard> clipboard, Sleeper sleeper, long consumeDelay) {
    this.clipboard = clipboard;
    this.sleeper = sleeper;
    this.consumeDelay = consumeDelay;
  }

  /** 画面操作の支援に関するpasteを処理する。 */
  public void paste(String text, PasteGesture gesture) throws Exception {
    LOCK.lockInterruptibly();
    try {
      Clipboard board = clipboard.get();
      Transferable original = snapshot(board.getContents(null));
      String token = UUID.randomUUID().toString();
      Transferable inserted =
          new Transferable() {
            /** 画面操作の支援で参照する引き継ぎデータFlavorsを返す。 */
            public DataFlavor[] getTransferDataFlavors() {
              return new DataFlavor[] {DataFlavor.stringFlavor, MARKER};
            }

            /** 画面操作の支援において、データFlavor対応済みかどうかを判定する。 */
            public boolean isDataFlavorSupported(DataFlavor flavor) {
              return flavor.equals(DataFlavor.stringFlavor) || flavor.equals(MARKER);
            }

            /** 画面操作の支援で参照する引き継ぎデータを返す。 */
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
      } catch (Exception error) {
        failure = error;
        throw error;
      } finally {
        try {
          if (owns(board, token))
            board.setContents(original == null ? new StringSelection("") : original, null);
        } catch (Exception restoreError) {
          if (failure != null) failure.addSuppressed(restoreError);
          else throw restoreError;
        }
      }
    } finally {
      LOCK.unlock();
    }
  }

  /** 画面操作の支援において、ownsに該当するか判定する。 */
  private static boolean owns(Clipboard board, String token) throws Exception {
    var current = board.getContents(null);
    return current != null
        && current.isDataFlavorSupported(MARKER)
        && token.equals(current.getTransferData(MARKER));
  }

  /**
   * 現在の状態を、後から参照できる記録として取り出す。
   *
   * <p>System Transferables may read lazily from native storage: materialize before replacing it.
   */
  private static Transferable snapshot(Transferable original) throws Exception {
    if (original == null) return null;
    var values = new java.util.LinkedHashMap<DataFlavor, Supplier<Object>>();
    for (DataFlavor flavor : original.getTransferDataFlavors()) {
      Object value = original.getTransferData(flavor);
      if (value instanceof java.io.InputStream stream) {
        byte[] bytes;
        try (stream) {
          bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
        }
        if (bytes.length > 8 * 1024 * 1024)
          throw new IllegalStateException("Clipboard stream too large to preserve");
        values.put(flavor, () -> new java.io.ByteArrayInputStream(bytes));
      } else if (value instanceof java.io.Reader reader) {
        var text = new StringBuilder();
        try (reader) {
          char[] buffer = new char[4096];
          int count;
          while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
            // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
            if (text.length() > 4 * 1024 * 1024)
              throw new IllegalStateException("Clipboard reader too large to preserve");
          }
        }
        String saved = text.toString();
        values.put(flavor, () -> new java.io.StringReader(saved));
      } else {
        values.put(flavor, () -> value);
      }
    }
    return new Transferable() {
      /** 画面操作の支援で参照する引き継ぎデータFlavorsを返す。 */
      public DataFlavor[] getTransferDataFlavors() {
        return values.keySet().toArray(DataFlavor[]::new);
      }

      /** 画面操作の支援において、データFlavor対応済みかどうかを判定する。 */
      public boolean isDataFlavorSupported(DataFlavor flavor) {
        return values.containsKey(flavor);
      }

      /** 画面操作の支援で参照する引き継ぎデータを返す。 */
      public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!values.containsKey(flavor)) throw new UnsupportedFlavorException(flavor);
        return values.get(flavor).get();
      }
    };
  }
}
