/** PCM16の符号、端数の拒否、部分読み取りの範囲を確認する小さな検証。 */
public final class PcmTest {
  /** PCM16の符号、端数の拒否、部分読み取りの範囲を確認する小さな検証。 */
  public static void main(String[] args) {
    // 正負の振幅と下位バイトを含む音声が、符号を変えずに認識用の値へ変換されることを確認する。
    float[] result = Pcm.decode(new byte[] {(byte) 0xff, 0, 0, (byte) 0x80, (byte) 0xff, 0x7f}, 6);
    if (result[0] != 255f / 32768 || result[1] != -1f || result[2] != 32767f / 32768)
      throw new AssertionError("signed low byte corrupted");
    // 途中で切れたPCM16を受け付けず、古いバッファーの値を部分読み取りへ混ぜないことを確認する。
    try {
      Pcm.decode(new byte[4], 3);
      throw new AssertionError("odd bytes accepted");
    } catch (IllegalArgumentException expected) {
    }
    if (Pcm.decode(new byte[6], 2).length != 1)
      throw new AssertionError("partial read includes stale bytes");
    System.out.println("PCM checks: 3 passed");
  }
}
