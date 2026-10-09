package dev.mikoto2000.rei.voice;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;

/** Pinned offline runtime, loaded lazily. Building and starting Rei needs no JNI or model download. */
public final class SherpaBackendFactory implements VoiceBackendFactory, AutoCloseable {
  public record Asset(String path, long bytes, String sha256) {}
  public static final List<Asset> ASSETS = List.of(
    new Asset("jvm.jar",187490,"77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b"),
    new Asset("native.jar",8277046,"33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039"),
    new Asset("models/base-encoder.int8.onnx",29120534,"0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
    new Asset("models/base-decoder.int8.onnx",130672026,"9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
    new Asset("models/base-tokens.txt",816730,"b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
    new Asset("models/silero_vad.onnx",643854,"9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
  );
  private final Supplier<Path> directory;
  private URLClassLoader loader;
  private int users;
  private boolean closing;
  public SherpaBackendFactory(Supplier<Path> directory) { this.directory = Objects.requireNonNull(directory); }
  public static void verify(Path root) throws IOException {
    for (var asset : ASSETS) {
      Path path = root.resolve(asset.path());
      if (!Files.isRegularFile(path) || Files.size(path)!=asset.bytes()) throw new IOException("Missing or invalid voice asset: "+asset.path());
      try (var input=Files.newInputStream(path)) {
        var digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[65536]; int count;
        while ((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
        if (!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256())) throw new IOException("Voice asset SHA-256 mismatch: "+asset.path());
      } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
  }
  public synchronized VoiceBackend open(VoiceSettings settings) throws Exception {
    if (closing) throw new IllegalStateException("Voice runtime closed");
    Path root = directory.get().toAbsolutePath().normalize();
    verify(root);
    if (loader==null) loader=new URLClassLoader(new URL[]{root.resolve("jvm.jar").toUri().toURL(),
        root.resolve("native.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader());
    if (!loader.getUnnamedModule().isNativeAccessEnabled())
      throw new IllegalStateException("Start Java with --enable-native-access=ALL-UNNAMED before /voice on");
    Object vad=null;
    try {
      var silero=builder("SileroVadModelConfig");
      set(silero,"setModel",root.resolve("models/silero_vad.onnx").toString());
      set(silero,"setThreshold",settings.threshold());
      set(silero,"setMinSilenceDuration",settings.silenceMs()/1000f);
      set(silero,"setMinSpeechDuration",settings.minSpeechMs()/1000f);
      set(silero,"setMaxSpeechDuration",settings.maxSpeechMs()/1000f);
      set(silero,"setWindowSize",VoiceSettings.WINDOW);
      var vadConfig=builder("VadModelConfig");
      set(vadConfig,"setSileroVadModelConfig",call(silero,"build"));
      set(vadConfig,"setSampleRate",VoiceSettings.SAMPLE_RATE);
      set(vadConfig,"setNumThreads",1); set(vadConfig,"setProvider","cpu"); set(vadConfig,"setDebug",false);
      vad=construct("Vad",call(vadConfig,"build"));
      var whisper=builder("OfflineWhisperModelConfig");
      set(whisper,"setEncoder",root.resolve("models/base-encoder.int8.onnx").toString());
      set(whisper,"setDecoder",root.resolve("models/base-decoder.int8.onnx").toString());
      set(whisper,"setLanguage","ja"); set(whisper,"setTask","transcribe");
      var model=builder("OfflineModelConfig"); set(model,"setWhisper",call(whisper,"build"));
      set(model,"setTokens",root.resolve("models/base-tokens.txt").toString());
      set(model,"setNumThreads",1); set(model,"setProvider","cpu"); set(model,"setDebug",false);
      var config=builder("OfflineRecognizerConfig"); set(config,"setOfflineModelConfig",call(model,"build"));
      set(config,"setDecodingMethod","greedy_search");
      Object recognizer=construct("OfflineRecognizer",call(config,"build"));
      Object detector=vad; users++;
      return new VoiceBackend(new VoiceActivityDetector() {
        public float probability(float[] frame) throws Exception { VoicePcm.validateFrame(frame); return ((Number)call(detector,"compute",frame)).floatValue(); }
        public void close() { try { release(detector); } finally { userReleased(); } }
      },new SpeechRecognizer() {
        public String recognize(SpeechSegment segment) throws Exception {
          Object stream=call(recognizer,"createStream");
          try {
            call(stream,"acceptWaveform",segment.samples(),VoiceSettings.SAMPLE_RATE);
            call(recognizer,"decode",stream);
            return (String)call(call(recognizer,"getResult",stream),"getText");
          } finally { release(stream); }
        }
        public void close() {
          release(recognizer);
        }
      });
    } catch (Exception | LinkageError e) {
      if (vad!=null) release(vad);
      throw e;
    }
  }
  private Object builder(String name) throws Exception { return invoke(type(name).getMethod("builder"),null); }
  private Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.k2fsa.sherpa.onnx."+name,true,loader); }
  private Object construct(String name,Object config) throws Exception {
    try { return type(name).getConstructor(config.getClass()).newInstance(config); }
    catch (InvocationTargetException e) { return rethrow(e.getCause()); }
  }
  private void set(Object target,String name,Object value) throws Exception { call(target,name,value); }
  private Object call(Object target,String name,Object... args) throws Exception {
    for (var method:target.getClass().getMethods()) {
      if (!method.getName().equals(name)||method.getParameterCount()!=args.length) continue;
      var types=method.getParameterTypes(); boolean match=true;
      for (int i=0;i<types.length;i++) {
        Class<?> type=types[i].isPrimitive()?box(types[i]):types[i];
        if (!type.isInstance(args[i])) { match=false; break; }
      }
      if (match) return invoke(method,target,args);
    }
    throw new NoSuchMethodException(target.getClass().getName()+"."+name);
  }
  private static Class<?> box(Class<?> type) {
    if(type==int.class)return Integer.class;if(type==float.class)return Float.class;
    if(type==boolean.class)return Boolean.class;if(type==long.class)return Long.class;
    if(type==double.class)return Double.class;return type;
  }
  private static Object invoke(Method method,Object target,Object... args) throws Exception {
    try { return method.invoke(target,args); } catch (InvocationTargetException e) { return rethrow(e.getCause()); }
  }
  private static Object rethrow(Throwable cause) throws Exception {
    if(cause instanceof Exception e)throw e;if(cause instanceof Error e)throw e;
    throw new IllegalStateException(cause);
  }
  private void release(Object target) {
    try { call(target,"release"); } catch (Exception e) { throw new IllegalStateException("Native release failed",e); }
  }
  private synchronized void userReleased() {
    users--; if (closing && users==0) closeLoader();
  }
  private void closeLoader() {
    if(loader!=null) try { loader.close(); } catch(IOException e) { throw new UncheckedIOException(e); }
  }
  public synchronized void close() { closing=true; if(users==0)closeLoader(); }
}