package dev.mikoto2000.rei.chatgpt;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

/**
 * ChatGPTとの連携に必要なWindowsDpapiProtectorを扱う。
 *
 * <p>Current-user DPAPI; no plaintext fallback and no machine-wide protection.
 */
public final class WindowsDpapiProtector implements SecretProtector {
  /** ChatGPTとの連携で用いる保護を返す。 */
  public byte[] protect(byte[] plaintext) {
    check();
    try {
      return Crypt32Util.cryptProtectData(plaintext);
    } catch (Throwable e) {
      throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
    }
  }

  /** ChatGPTとの連携で用いるunprotectを返す。 */
  public byte[] unprotect(byte[] ciphertext) {
    check();
    try {
      return Crypt32Util.cryptUnprotectData(ciphertext);
    } catch (Throwable e) {
      throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
    }
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  private void check() {
    if (!Platform.isWindows()) throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
  }
}
