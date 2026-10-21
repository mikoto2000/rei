package dev.mikoto2000.rei.chatgpt;

/** ChatGPTとの連携に必要な秘密情報Protectorを扱う。 */
public interface SecretProtector {
  /** ChatGPTとの連携で用いる保護を返す。 */
  byte[] protect(byte[] plaintext);

  /** ChatGPTとの連携で用いるunprotectを返す。 */
  byte[] unprotect(byte[] ciphertext);
}
