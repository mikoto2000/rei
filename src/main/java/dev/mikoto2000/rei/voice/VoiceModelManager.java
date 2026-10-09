package dev.mikoto2000.rei.voice;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One asynchronous, explicitly approved transaction. Only a verified whole directory is activated. */
public final class VoiceModelManager implements AutoCloseable {
  public enum State { MISSING, CORRUPT, DOWNLOADING, VERIFYING, READY, FAILED, CANCELLED, CLOSED }
  public record Status(State state,long bytes,long totalBytes,String asset,int attempt,String failure) {}
  private final Path root;
  private final VoiceModelManifest manifest;
  private final VoiceAssetTransport transport;
  private final Consumer<Status> listener;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"voice-model-download");t.setDaemon(true);return t;});
  private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"voice-model-deadline");t.setDaemon(true);return t;});
  private volatile Status status;
  private volatile boolean busy;
  private boolean closed;
  private VoiceAssetTransport.Cancellation cancellation;
  private volatile Thread owner;
  private long lastProgress;
  public VoiceModelManager(Path root,VoiceModelManifest manifest,VoiceAssetTransport transport,Consumer<Status> listener) {
    this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();this.manifest=Objects.requireNonNull(manifest);
    this.transport=Objects.requireNonNull(transport);this.listener=Objects.requireNonNull(listener);
    status=new Status(State.MISSING,0,manifest.totalBytes(),"",0,"");
  }
  public VoiceModelManifest manifest(){return manifest;}
  public Status status(){return status;}
  public boolean busy(){return busy;}
  /** Hashes are checked on every reuse, including manually placed bundles, without loading JNI. */
  public Path readyDirectory() throws IOException {
    Path managed=VoiceModelManifest.assetPath(root,"managed/"+manifest.id());
    try {manifest.verify(managed);return verifiedDirectory(managed);}catch(IOException invalidManaged) {
      try {manifest.verify(root);return verifiedDirectory(root);}catch(IOException invalidManual){
        synchronized(this) {
          if(!busy&&!closed&&(status.state()==State.READY||status.state()==State.MISSING||status.state()==State.CORRUPT)) {
            boolean partial=Files.exists(managed,LinkOption.NOFOLLOW_LINKS)||manifest.assets().stream().anyMatch(a->Files.exists(root.resolve(a.path()),LinkOption.NOFOLLOW_LINKS));
            update(partial?State.CORRUPT:State.MISSING,0,"",0,partial?"cached bundle failed integrity":"");
          }
        }
        throw new IOException("Voice bundle missing or corrupt; use /voice models info and install",invalidManual);
      }
    }
  }
  private synchronized Path verifiedDirectory(Path directory) {
    if(!busy&&!closed&&status.state()!=State.READY)update(State.READY,manifest.totalBytes(),"",0,"");
    return directory;
  }
  public synchronized boolean install(String approvedManifestId) {
    if(!manifest.id().equals(approvedManifestId))throw new IllegalArgumentException("Approve the displayed manifest ID with /voice models install --approve ID");
    if(closed)throw new IllegalStateException("Voice model manager closed");
    if(busy)throw new IllegalStateException("Voice download already active");
    try {readyDirectory();update(State.READY,manifest.totalBytes(),"",0,"");return false;}catch(IOException unavailable){}
    cancellation=new VoiceAssetTransport.Cancellation();busy=true;update(State.DOWNLOADING,0,"",0,"");
    var token=cancellation;worker.execute(()->download(token));return true;
  }
  private void update(State state,long bytes,String asset,int attempt,String failure) {
    status=new Status(state,bytes,manifest.totalBytes(),asset,attempt,failure);
    long now=System.nanoTime();
    if(state!=State.DOWNLOADING||bytes==0||bytes==manifest.totalBytes()||now-lastProgress>TimeUnit.SECONDS.toNanos(1)) {
      lastProgress=now;try{listener.accept(status);}catch(RuntimeException ignored){}
    }
  }
  public boolean cancel() {
    VoiceAssetTransport.Cancellation token;Thread thread;
    synchronized(this){if(!busy||status.state()==State.READY)return false;token=cancellation;token.cancel();thread=owner;}
    token.cancel();if(thread!=null)thread.interrupt();return true;
  }
  private void download(VoiceAssetTransport.Cancellation token) {
    Path stage=null;State result=State.FAILED;String failure="download failed";long completed=0;
    owner=Thread.currentThread();ScheduledFuture<?> timeout=null;
    try {
      token.check();timeout=deadlines.schedule(this::cancel,Duration.ofMinutes(15).toMillis(),TimeUnit.MILLISECONDS);
      VoiceModelManifest.assetPath(root,"staging/safety-check");
      Files.createDirectories(root.resolve("staging"));stage=Files.createTempDirectory(root.resolve("staging"),"download-");
      for(var asset:manifest.assets()) {
        token.check();Path target=VoiceModelManifest.assetPath(stage,asset.path());Files.createDirectories(target.getParent());
        IOException last=null;final long prior=completed;
        for(int attempt=1;attempt<=3;attempt++) {
          token.check();final int number=attempt;update(State.DOWNLOADING,prior,asset.path(),attempt,"");
          try {
            transport.download(asset,target,token,count->update(State.DOWNLOADING,prior+Math.min(count,asset.bytes()),asset.path(),number,""));
            last=null;break;
          }catch(IOException error) {
            token.check();last=error;Files.deleteIfExists(target);
            if(attempt<3)Thread.sleep(250L*attempt);
          }
        }
        if(last!=null)throw last;
        VoiceModelManifest.verifyAsset(asset,target);completed+=asset.bytes();
      }
      token.check();update(State.VERIFYING,completed,"",0,"");manifest.verify(stage);
      synchronized(this) {
        token.check();if(closed)throw new InterruptedIOException("Voice manager closed");
        Path active=VoiceModelManifest.assetPath(root,"managed/"+manifest.id());Files.createDirectories(active.getParent());
        Path backup=null;
        if(Files.exists(active,LinkOption.NOFOLLOW_LINKS)) {
          backup=VoiceModelManifest.assetPath(root,"retained/"+manifest.id()+"-"+UUID.randomUUID());Files.createDirectories(backup.getParent());
          Files.move(active,backup,StandardCopyOption.ATOMIC_MOVE);
        }
        try {Files.move(stage,active,StandardCopyOption.ATOMIC_MOVE);stage=null;}
        catch(IOException error){if(backup!=null&&!Files.exists(active))Files.move(backup,active,StandardCopyOption.ATOMIC_MOVE);throw error;}
        result=State.READY;failure="";update(State.READY,completed,"",0,"");
      }
    }catch(InterruptedException error){Thread.currentThread().interrupt();result=State.CANCELLED;failure="cancelled";}
    catch(Exception error){result=token.cancelled()?State.CANCELLED:State.FAILED;failure=token.cancelled()?"cancelled":"download, size, integrity or activation failed";}
    finally {
      if(timeout!=null)timeout.cancel(false);token.cancel();
      if(stage!=null)try{deleteStage(stage);}catch(IOException error){failure="staging cleanup failed";if(result!=State.CANCELLED)result=State.FAILED;}
      synchronized(this){owner=null;update(closed?State.CLOSED:result,completed,"",0,failure);busy=false;cancellation=null;notifyAll();}
      Thread.interrupted();
    }
  }
  private void deleteStage(Path stage) throws IOException {
    Path staging=root.resolve("staging").normalize();Path checked=stage.toAbsolutePath().normalize();
    if(!checked.startsWith(staging)||checked.equals(staging))throw new IOException("Unsafe staging cleanup");
    try(var paths=Files.walk(checked)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
  }
  public void close() {
    synchronized(this){if(closed)return;closed=true;}
    cancel();deadlines.shutdownNow();worker.shutdown();
    try{worker.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    synchronized(this){if(!busy)update(State.CLOSED,status.bytes(),"",0,"");}
  }
}