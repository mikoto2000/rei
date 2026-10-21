package dev.mikoto2000.rei.activity;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * 作業状況の把握に必要なWindows入力利用可否の確認を扱う。
 *
 * <p>Read-only, current-session signals. Lazy native loading keeps other platforms usable.
 */
final class WindowsInputProbe {
  /** 作業状況の把握に必要な利用者を扱う。 */
  interface User extends StdCallLibrary {
    /** 作業状況の把握において、取得直近入力付随情報に該当するか判定する。 */
    boolean GetLastInputInfo(LastInput value);

    /** 作業状況の把握で参照する前面ウィンドウを返す。 */
    Pointer GetForegroundWindow();

    /** 必要な対象を利用できるよう、入力デスクトップの利用を開始する。 */
    Pointer OpenInputDesktop(int flags, boolean inherit, int access);

    /** 作業状況の把握において、取得利用者対象データ情報Wに該当するか判定する。 */
    boolean GetUserObjectInformationW(
        Pointer object, int index, Pointer buffer, int bytes, IntByReference needed);

    /** 作業状況の把握において、終了デスクトップに該当するか判定する。 */
    boolean CloseDesktop(Pointer desktop);
  }

  /** 作業状況の把握に必要なKernelを扱う。 */
  interface Kernel extends StdCallLibrary {
    /** 作業状況の把握で参照する定期確認件数64を返す。 */
    long GetTickCount64();

    /** 作業状況の把握において、検索条件Unbiased中断時刻に該当するか判定する。 */
    boolean QueryUnbiasedInterruptTime(LongByReference working);
  }

  /** 作業状況の把握に必要な直近入力を扱う。 */
  @Structure.FieldOrder({"cbSize", "dwTime"})
  public static class LastInput extends Structure {
    /** cb大きさ／dw時刻。 */
    public int cbSize, dwTime;

    /** 既定の状態でLastInputを初期化する。 */
    public LastInput() {
      cbSize = size();
    }
  }

  /** 作業状況の把握に必要なAPIを扱う。 */
  private static final class Api {
    /** 利用者。 */
    static final User USER = Native.load("user32", User.class, W32APIOptions.DEFAULT_OPTIONS);

    /** OSの入力状態を問い合わせる窓口。 */
    static final Kernel KERNEL =
        Native.load("kernel32", Kernel.class, W32APIOptions.DEFAULT_OPTIONS);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  static DesktopActivityObserver.Lightweight read() {
    if (!Platform.isWindows()) return null;
    var input = new LastInput();
    boolean valid = Api.USER.GetLastInputInfo(input);
    long ticks = Api.KERNEL.GetTickCount64();
    var working = new LongByReference();
    valid &= Api.KERNEL.QueryUnbiasedInterruptTime(working);
    var foreground = Api.USER.GetForegroundWindow();
    String desktop = null;
    var handle =
        Api.USER.OpenInputDesktop(0, false, 1); // DESKTOP_READOBJECTS, never switch desktops.
    if (handle != null)
      try (var buffer = new Memory(512)) {
        if (Api.USER.GetUserObjectInformationW(handle, 2, buffer, 512, new IntByReference()))
          desktop = buffer.getWideString(0);
      } finally {
        Api.USER.CloseDesktop(handle);
      }
    return new DesktopActivityObserver.Lightweight(
        Integer.toUnsignedLong(input.dwTime),
        ticks,
        foreground == null ? null : Long.toUnsignedString(Pointer.nativeValue(foreground)),
        desktop,
        "Winlogon".equalsIgnoreCase(desktop),
        valid && foreground != null && desktop != null,
        working.getValue() / 10000);
  }
}
