public final class Pcm {
  private Pcm() {}
  public static float[] decode(byte[] bytes, int count) {
    if(count < 0 || count > bytes.length || count % 2 != 0) throw new IllegalArgumentException("Invalid PCM16 length");
    float[] result = new float[count/2];
    for(int i=0;i<result.length;i++) result[i] = (short)((bytes[2*i]&255) | (bytes[2*i+1]<<8))/32768f;
    return result;
  }
}
