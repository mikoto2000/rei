/** 音声認識の試作で使うPCM16データを、振幅を保った数値列へ変換する。 */
public final class Pcm {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private Pcm() {}

  /** 読み取った範囲のPCM16を、符号と振幅を保った音声サンプルへ変換する。 */
  public static float[] decode(byte[] bytes, int count) {
    // PCM16の一つのサンプルは2バイトなので、途中で切れた入力や読み取り範囲外の指定を受け付けない。
    if (count < 0 || count > bytes.length || count % 2 != 0)
      throw new IllegalArgumentException("Invalid PCM16 length");
    // 未使用のバッファーを音声として認識しないよう、実際に読み取った範囲だけを変換する。
    float[] result = new float[count / 2];
    for (int i = 0; i < result.length; i++)
      result[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8)) / 32768f;
    return result;
  }
}
