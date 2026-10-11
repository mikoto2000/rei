package dev.mikoto2000.rei.chatgpt;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

/** Current-user DPAPI; no plaintext fallback and no machine-wide protection. */
public final class WindowsDpapiProtector implements SecretProtector {
  public byte[] protect(byte[] plaintext) {
    check();
    try {
      return Crypt32Util.cryptProtectData(plaintext);
    } catch (Throwable e) {
      throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
    }
  }

  public byte[] unprotect(byte[] ciphertext) {
    check();
    try {
      return Crypt32Util.cryptUnprotectData(ciphertext);
    } catch (Throwable e) {
      throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
    }
  }

  private void check() {
    if (!Platform.isWindows()) throw ChatGptException.of(ChatGptException.Code.STORE_UNAVAILABLE);
  }
}
