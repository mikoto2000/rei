package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.file.Path;
import java.util.function.LongConsumer;
@FunctionalInterface
public interface VoiceAssetTransport {
  void download(VoiceModelManifest.Asset asset,Path target,Cancellation cancellation,LongConsumer progress) throws IOException;
  /** Closing the current HTTP body and interrupting the owner makes cancellation bounded. */
  final class Cancellation {
    private volatile boolean cancelled;
    private InputStream body;
    public boolean cancelled(){return cancelled;}
    public void check() throws InterruptedIOException {
      if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Voice download cancelled");
    }
    public synchronized void attach(InputStream input) throws IOException {
      if(cancelled){input.close();throw new InterruptedIOException("Voice download cancelled");}body=input;
    }
    public synchronized void detach(){body=null;}
    public void cancel() {
      InputStream input;synchronized(this){cancelled=true;input=body;body=null;}
      if(input!=null)try{input.close();}catch(IOException ignored){}
    }
  }
}