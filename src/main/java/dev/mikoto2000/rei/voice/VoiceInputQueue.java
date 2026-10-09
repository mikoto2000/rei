package dev.mikoto2000.rei.voice;
import java.util.concurrent.*;
/** Bounded complete-segment queue; offer never waits for ASR or an Agent. */
public final class VoiceInputQueue {
  private final ArrayBlockingQueue<SpeechSegment> queue=new ArrayBlockingQueue<>(2);
  public boolean offer(SpeechSegment segment) { return queue.offer(segment); }
  public SpeechSegment poll(long timeout,TimeUnit unit) throws InterruptedException { return queue.poll(timeout,unit); }
  public int size() { return queue.size(); }
  public void clear() { queue.clear(); }
}