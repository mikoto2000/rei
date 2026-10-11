package dev.mikoto2000.rei.chatgpt;

public interface SecretProtector {
  byte[] protect(byte[] plaintext);

  byte[] unprotect(byte[] ciphertext);
}
