package dev.mikoto2000.rei.voice;
import java.util.function.BooleanSupplier;
/** Local output must poll selection/cancellation and stop only its own playback. */
@FunctionalInterface
public interface VoiceSpeechOutput extends AutoCloseable {
  void speak(String voice,String text,BooleanSupplier current) throws Exception;
  default void stop() {}
  @Override default void close(){stop();}
}
