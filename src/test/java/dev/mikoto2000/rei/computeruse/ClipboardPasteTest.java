package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import org.junit.jupiter.api.Test;

/** クリップボードPasteの結果と状態の契約を検証するテスト。 */
class ClipboardPasteTest {
  /** 画面操作の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void unicodeIsAvailableDuringPasteAndOriginalTransferableIsRestored() throws Exception {
    var clipboard = new Clipboard("test");
    var original = new StringSelection("original");
    clipboard.setContents(original, null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    paste.paste("日本語😀", () -> assertEquals("日本語😀", clipboard.getData(DataFlavor.stringFlavor)));
    assertEquals("original", clipboard.getData(DataFlavor.stringFlavor));
  }

  /** 画面操作の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restoresOnDispatchFailureWithoutOverwritingConcurrentChanges() throws Exception {
    var clipboard = new Clipboard("test");
    var original = new StringSelection("original");
    clipboard.setContents(original, null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    assertThrows(
        IllegalStateException.class,
        () ->
            paste.paste(
                "new",
                () -> {
                  throw new IllegalStateException();
                }));
    assertEquals("original", clipboard.getData(DataFlavor.stringFlavor));
    var external = new StringSelection("external");
    paste.paste("new", () -> clipboard.setContents(external, null));
    assertSame(external, clipboard.getContents(null));
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void acquisitionFailureNeverDispatches() {
    var paste =
        new ClipboardPaste(
            () -> {
              throw new IllegalStateException("busy");
            },
            ms -> {},
            10);
    assertThrows(IllegalStateException.class, () -> paste.paste("new", () -> fail()));
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void snapshotsLazyNativeContentsBeforeReplacingClipboard() throws Exception {
    var clipboard = new Clipboard("test");
    var stillAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);
    var original =
        new Transferable() {
          /** 検証用に、画面操作の支援で参照する引き継ぎデータFlavorsを返す。 */
          public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.stringFlavor};
          }

          /** 検証用に、画面操作の支援において、データFlavor対応済みかどうかを判定する。 */
          public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.stringFlavor.equals(flavor);
          }

          /** 検証用に、画面操作の支援で参照する引き継ぎデータを返す。 */
          public Object getTransferData(DataFlavor flavor) {
            if (!stillAvailable.get())
              throw new IllegalStateException("Native clipboard contents were replaced");
            return "before";
          }
        };
    clipboard.setContents(original, null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    paste.paste("after", () -> stillAvailable.set(false));
    assertEquals("before", clipboard.getData(DataFlavor.stringFlavor));
  }

  /** 復元失敗情報を伝えることを確認する。 */
  @Test
  void detectsConcurrentReplacementBeforePasteAndReportsRestoreFailure() throws Exception {
    var external = new StringSelection("external");
    var clipboard =
        new Clipboard("test") {
          /** 検証に使う保存する。 */
          int writes;

          /** 検証用に、画面操作の支援で使用するContentsを設定する。 */
          @Override
          public synchronized void setContents(Transferable value, ClipboardOwner owner) {
            super.setContents(++writes == 2 ? external : value, owner);
          }
        };
    clipboard.setContents(new StringSelection("before"), null);
    var paste = new ClipboardPaste(() -> clipboard, ms -> {}, 10);
    assertThrows(
        IllegalStateException.class,
        () -> paste.paste("after", () -> fail("Must not paste someone else's clipboard")));
    assertSame(external, clipboard.getContents(null));
    var broken =
        new Clipboard("test") {
          /** 検証に使う保存する。 */
          int writes;

          /** 検証用に、画面操作の支援で使用するContentsを設定する。 */
          @Override
          public synchronized void setContents(Transferable value, ClipboardOwner owner) {
            if (++writes == 3) throw new IllegalStateException("Restore failed");
            super.setContents(value, owner);
          }
        };
    broken.setContents(new StringSelection("before"), null);
    assertThrows(
        IllegalStateException.class,
        () -> new ClipboardPaste(() -> broken, ms -> {}, 10).paste("after", () -> {}));
  }
}
