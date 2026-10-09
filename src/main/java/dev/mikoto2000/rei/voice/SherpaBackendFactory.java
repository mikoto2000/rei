package dev.mikoto2000.rei.voice;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Pinned offline runtime, loaded lazily. Building and starting Rei needs no JNI or model download. */
public final class SherpaBackendFactory implements VoiceBackendFactory, AutoCloseable {
  public record Asset(String path, long bytes, String sha256) {}
  public static final List<Asset> ASSETS = List.of(
    new Asset("jvm.jar",187490,"77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b"),
    new Asset("native.jar",8277046,"33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039"),
    new Asset("models/turbo-encoder.onnx",735920,"1b960f278564fb8bbacd544d4f85f4dd6d8a64d3aa89543d8f2c4021c926f976"),
    new Asset("models/turbo-encoder.weights",2600325120L,"746f879ecf066450ab0cdecc05383380b85157270ff6c0a9fb7cfdd917036e12"),
    new Asset("models/turbo-decoder.onnx",636209532,"b24db5d90fa230c5eaa6b823d74862ced9e0d1dc3e01ec46601968e8db0e09ec"),
    new Asset("models/turbo-tokens.txt",816730,"b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
    new Asset("models/silero_vad.onnx",643854,"9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
  );
  private final Supplier<Path> directory;
  private final Supplier<VoiceInferenceOptions> inference;
  private URLClassLoader loader;
  private int users;
  private boolean closing;
  public SherpaBackendFactory(Supplier<Path> directory) {this(directory,VoiceInferenceOptions::defaults);}
  public SherpaBackendFactory(Supplier<Path> directory,Supplier<VoiceInferenceOptions> inference) {this.directory=Objects.requireNonNull(directory);this.inference=Objects.requireNonNull(inference);}
  public static void verify(Path root) throws IOException { VoiceModelManifest.pinned().verify(root); }
  /** Verify each worker's dependencies before JNI load, without making VAD hash the 3 GB ASR model. */
  static List<VoiceModelManifest.Asset> roleAssets(boolean withVad,boolean withRecognizer) {
    return VoiceModelManifest.pinned().assets().stream().filter(a->a.path().endsWith(".jar")
      ||(withVad&&a.path().equals("models/silero_vad.onnx"))
      ||(withRecognizer&&a.path().startsWith("models/turbo-"))).toList();
  }
  private static void verifyRole(Path root,boolean withVad,boolean withRecognizer) throws IOException {
    for(var asset:roleAssets(withVad,withRecognizer))
      VoiceModelManifest.verifyAsset(asset,VoiceModelManifest.assetPath(root,asset.path()));
  }
  public synchronized VoiceBackend open(VoiceSettings settings) throws Exception {return openRole(settings,true,true);}
  public synchronized VoiceBackend openVad(VoiceSettings settings) throws Exception {return openRole(settings,true,false);}
  public synchronized VoiceBackend openRecognizer(VoiceSettings settings) throws Exception {return openRole(settings,false,true);}
  private VoiceBackend openRole(VoiceSettings settings,boolean withVad,boolean withRecognizer) throws Exception {
    if (closing) throw new IllegalStateException("Voice runtime closed");
    var options=Objects.requireNonNull(inference.get());
    Path root = directory.get().toAbsolutePath().normalize();
    verifyRole(root,withVad,withRecognizer);
    if (loader==null) loader=new URLClassLoader(new URL[]{root.resolve("jvm.jar").toUri().toURL(),
        root.resolve("native.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader());
    if (!loader.getUnnamedModule().isNativeAccessEnabled())
      throw new IllegalStateException("Start Java with --enable-native-access=ALL-UNNAMED before /voice on");
    Object vad=null,recognizer=null;
    try {
      if(withVad) {
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
      }
      if(withRecognizer) {
      var whisper=builder("OfflineWhisperModelConfig");
      set(whisper,"setEncoder",root.resolve("models/turbo-encoder.onnx").toString());
      set(whisper,"setDecoder",root.resolve("models/turbo-decoder.onnx").toString());
      set(whisper,"setLanguage","ja"); set(whisper,"setTask","transcribe"); set(whisper,"setTailPaddings",options.tailFrames());
      var model=builder("OfflineModelConfig"); set(model,"setWhisper",call(whisper,"build"));
      set(model,"setTokens",root.resolve("models/turbo-tokens.txt").toString());
      set(model,"setNumThreads",options.threads()); set(model,"setProvider","cpu"); set(model,"setDebug",false);
      var config=builder("OfflineRecognizerConfig"); set(config,"setOfflineModelConfig",call(model,"build"));
      set(config,"setDecodingMethod","greedy_search");
      recognizer=construct("OfflineRecognizer",call(config,"build"));
      }
      Object decoder=recognizer;
      Object detector=vad; users++;
      return new VoiceBackend(new VoiceActivityDetector() {
        public float probability(float[] frame) throws Exception { if(detector==null)throw new IllegalStateException("VAD is not loaded in this worker"); VoicePcm.validateFrame(frame); return ((Number)call(detector,"compute",frame)).floatValue(); }
        public void close() { try { if(detector!=null)release(detector); } finally { userReleased(); } }
      },new SpeechRecognizer() {
        public String recognize(SpeechSegment segment) throws Exception {
          if(decoder==null)throw new IllegalStateException("ASR is not loaded in this worker");
          Object stream=call(decoder,"createStream");
          try {
            call(stream,"acceptWaveform",segment.samples(),VoiceSettings.SAMPLE_RATE);
            call(decoder,"decode",stream);
            return (String)call(call(decoder,"getResult",stream),"getText");
          } finally { release(stream); }
        }
        public void close() {
          if(decoder!=null)release(decoder);
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