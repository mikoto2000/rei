public final class PcmTest {
  public static void main(String[] args) {
    float[] result = Pcm.decode(new byte[] {(byte)0xff,0,0,(byte)0x80,(byte)0xff,0x7f}, 6);
    if(result[0] != 255f/32768 || result[1] != -1f || result[2] != 32767f/32768) throw new AssertionError("signed low byte corrupted");
    try { Pcm.decode(new byte[4],3); throw new AssertionError("odd bytes accepted"); } catch(IllegalArgumentException expected) {}
    if(Pcm.decode(new byte[6],2).length != 1) throw new AssertionError("partial read includes stale bytes");
    System.out.println("PCM checks: 3 passed");
  }
}
