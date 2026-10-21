package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Sherpaバックエンドの情報を共通の形式で生成する。
 *
 * <p>Pinned offline runtime, loaded lazily. Building and starting Rei needs no JNI or model
 * download.
 */
public final class SherpaBackendFactory implements VoiceBackendFactory, AutoCloseable {
  /**
   * 音声による対話で受け渡す素材を表す。
   *
   * @param path パス
   * @param bytes バイト数
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   */
  public record Asset(String path, long bytes, String sha256) {}

  /** 素材一覧。 */
  public static final List<Asset> ASSETS =
      List.of(
          new Asset(
              "jvm.jar",
              187490,
              "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b"),
          new Asset(
              "native.jar",
              8277046,
              "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039"),
          new Asset(
              "models/turbo-encoder.onnx",
              735920,
              "1b960f278564fb8bbacd544d4f85f4dd6d8a64d3aa89543d8f2c4021c926f976"),
          new Asset(
              "models/turbo-encoder.weights",
              2600325120L,
              "746f879ecf066450ab0cdecc05383380b85157270ff6c0a9fb7cfdd917036e12"),
          new Asset(
              "models/turbo-decoder.onnx",
              636209532,
              "b24db5d90fa230c5eaa6b823d74862ced9e0d1dc3e01ec46601968e8db0e09ec"),
          new Asset(
              "models/turbo-tokens.txt",
              816730,
              "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
          new Asset(
              "models/silero_vad.onnx",
              643854,
              "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"));

  /** ディレクトリ。 */
  private final Supplier<Path> directory;

  /** 推定。 */
  private final Supplier<VoiceInferenceOptions> inference;

  /** 読み込みの窓口。 */
  private URLClassLoader loader;

  /** 利用者一覧。 */
  private int users;

  /** 終了中。 */
  private boolean closing;

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SherpaBackendFactory(Supplier<Path> directory) {
    this(directory, VoiceInferenceOptions::defaults);
  }

  /** 指定された値や利用先を使って、SherpaBackendFactoryを初期化する。 */
  public SherpaBackendFactory(Supplier<Path> directory, Supplier<VoiceInferenceOptions> inference) {
    this.directory = Objects.requireNonNull(directory);
    this.inference = Objects.requireNonNull(inference);
  }

  /** 要求された条件を満たすことを確認する。 */
  public static void verify(Path root) throws IOException {
    VoiceModelManifest.pinned().verify(root);
  }

  /**
   * 音声による対話で用いる役割素材一覧を返す。
   *
   * <p>Verify each worker's dependencies before JNI load, without making VAD hash the 3 GB ASR
   * model.
   */
  static List<VoiceModelManifest.Asset> roleAssets(boolean withVad, boolean withRecognizer) {
    return VoiceModelManifest.pinned().assets().stream()
        .filter(
            a ->
                a.path().endsWith(".jar")
                    || (withVad && a.path().equals("models/silero_vad.onnx"))
                    || (withRecognizer && a.path().startsWith("models/turbo-")))
        .toList();
  }

  /** 要求された条件を満たすか判断するため、役割を確認する。 */
  private static void verifyRole(Path root, boolean withVad, boolean withRecognizer)
      throws IOException {
    for (var asset : roleAssets(withVad, withRecognizer))
      VoiceModelManifest.verifyAsset(asset, VoiceModelManifest.assetPath(root, asset.path()));
  }

  /** 必要な対象を利用できるよう、Sherpaバックエンド生成元の利用を開始する。 */
  public synchronized VoiceBackend open(VoiceSettings settings) throws Exception {
    return openRole(settings, true, true);
  }

  /** 必要な対象を利用できるよう、発話区間の検出の利用を開始する。 */
  public synchronized VoiceBackend openVad(VoiceSettings settings) throws Exception {
    return openRole(settings, true, false);
  }

  /** 必要な対象を利用できるよう、Recognizerの利用を開始する。 */
  public synchronized VoiceBackend openRecognizer(VoiceSettings settings) throws Exception {
    return openRole(settings, false, true);
  }

  /** 必要な対象を利用できるよう、役割の利用を開始する。 */
  private VoiceBackend openRole(VoiceSettings settings, boolean withVad, boolean withRecognizer)
      throws Exception {
    if (closing) throw new IllegalStateException("Voice runtime closed");
    var options = Objects.requireNonNull(inference.get());
    Path root = directory.get().toAbsolutePath().normalize();
    verifyRole(root, withVad, withRecognizer);
    if (loader == null)
      loader =
          new URLClassLoader(
              new URL[] {
                root.resolve("jvm.jar").toUri().toURL(), root.resolve("native.jar").toUri().toURL()
              },
              ClassLoader.getPlatformClassLoader());
    if (!loader.getUnnamedModule().isNativeAccessEnabled())
      throw new IllegalStateException(
          "Start Java with --enable-native-access=ALL-UNNAMED before /voice on");
    Object vad = null, recognizer = null;
    WhisperByteVocabulary vocabulary = null;
    try {
      if (withVad) {
        var silero = builder("SileroVadModelConfig");
        set(silero, "setModel", root.resolve("models/silero_vad.onnx").toString());
        set(silero, "setThreshold", settings.threshold());
        set(silero, "setMinSilenceDuration", settings.silenceMs() / 1000f);
        set(silero, "setMinSpeechDuration", settings.minSpeechMs() / 1000f);
        set(silero, "setMaxSpeechDuration", settings.maxSpeechMs() / 1000f);
        set(silero, "setWindowSize", VoiceSettings.WINDOW);
        var vadConfig = builder("VadModelConfig");
        set(vadConfig, "setSileroVadModelConfig", call(silero, "build"));
        set(vadConfig, "setSampleRate", VoiceSettings.SAMPLE_RATE);
        set(vadConfig, "setNumThreads", 1);
        set(vadConfig, "setProvider", "cpu");
        set(vadConfig, "setDebug", false);
        vad = construct("Vad", call(vadConfig, "build"));
      }
      if (withRecognizer) {
        var whisper = builder("OfflineWhisperModelConfig");
        set(whisper, "setEncoder", root.resolve("models/turbo-encoder.onnx").toString());
        set(whisper, "setDecoder", root.resolve("models/turbo-decoder.onnx").toString());
        set(whisper, "setLanguage", "ja");
        set(whisper, "setTask", "transcribe");
        set(whisper, "setTailPaddings", options.tailFrames());
        var model = builder("OfflineModelConfig");
        set(model, "setWhisper", call(whisper, "build"));
        vocabulary = WhisperByteVocabulary.create(root.resolve("models/turbo-tokens.txt"));
        set(model, "setTokens", vocabulary.path().toString());
        set(model, "setNumThreads", options.threads());
        set(model, "setProvider", "cpu");
        set(model, "setDebug", false);
        var config = builder("OfflineRecognizerConfig");
        set(config, "setOfflineModelConfig", call(model, "build"));
        set(config, "setDecodingMethod", "greedy_search");
        recognizer = construct("OfflineRecognizer", call(config, "build"));
      }
      Object decoder = recognizer;
      WhisperByteVocabulary ownedVocabulary = vocabulary;
      Object detector = vad;
      users++;
      return new VoiceBackend(
          new VoiceActivityDetector() {
            /** 音声による対話で用いるprobabilityを返す。 */
            public float probability(float[] frame) throws Exception {
              if (detector == null)
                throw new IllegalStateException("VAD is not loaded in this worker");
              VoicePcm.validateFrame(frame);
              return ((Number) call(detector, "compute", frame)).floatValue();
            }

            /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
            public void close() {
              try {
                if (detector != null) release(detector);
              } finally {
                userReleased();
              }
            }
          },
          new SpeechRecognizer() {
            /** 音声による対話で用いるrecognizeを返す。 */
            public String recognize(SpeechSegment segment) throws Exception {
              if (decoder == null)
                throw new IllegalStateException("ASR is not loaded in this worker");
              Object stream = call(decoder, "createStream");
              try {
                call(stream, "acceptWaveform", segment.samples(), VoiceSettings.SAMPLE_RATE);
                call(decoder, "decode", stream);
                return WhisperByteVocabulary.decode(
                    (String) call(call(decoder, "getResult", stream), "getText"));
              } finally {
                release(stream);
              }
            }

            /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
            public void close() {
              try {
                if (decoder != null) release(decoder);
              } finally {
                if (ownedVocabulary != null)
                  try {
                    ownedVocabulary.close();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
              }
            }
          });
    } catch (Exception | LinkageError e) {
      try {
        if (recognizer != null) release(recognizer);
      } catch (RuntimeException cleanup) {
        e.addSuppressed(cleanup);
      }
      try {
        if (vad != null) release(vad);
      } catch (RuntimeException cleanup) {
        e.addSuppressed(cleanup);
      }
      if (vocabulary != null)
        try {
          vocabulary.close();
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      throw e;
    }
  }

  /** 音声による対話で用いる組み立てを返す。 */
  private Object builder(String name) throws Exception {
    return invoke(type(name).getMethod("builder"), null);
  }

  /** 音声による対話で用いる種別を返す。 */
  private Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName("com.k2fsa.sherpa.onnx." + name, true, loader);
  }

  /** 音声による対話で用いる構築を返す。 */
  private Object construct(String name, Object config) throws Exception {
    try {
      return type(name).getConstructor(config.getClass()).newInstance(config);
    } catch (InvocationTargetException e) {
      return rethrow(e.getCause());
    }
  }

  /** 以後の処理で使う値を、指定された内容へ切り替える。 */
  private void set(Object target, String name, Object value) throws Exception {
    call(target, name, value);
  }

  /** 音声による対話の要求を実行し、呼び出し元へ結果を返す。 */
  private Object call(Object target, String name, Object... args) throws Exception {
    for (var method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      var types = method.getParameterTypes();
      boolean match = true;
      for (int i = 0; i < types.length; i++) {
        Class<?> type = types[i].isPrimitive() ? box(types[i]) : types[i];
        if (!type.isInstance(args[i])) {
          match = false;
          break;
        }
      }
      if (match) return invoke(method, target, args);
    }
    throw new NoSuchMethodException(target.getClass().getName() + "." + name);
  }

  /** 音声による対話で用いるboxを返す。 */
  private static Class<?> box(Class<?> type) {
    if (type == int.class) return Integer.class;
    if (type == float.class) return Float.class;
    if (type == boolean.class) return Boolean.class;
    if (type == long.class) return Long.class;
    if (type == double.class) return Double.class;
    return type;
  }

  /** 音声による対話で用いる呼び出しを返す。 */
  private static Object invoke(Method method, Object target, Object... args) throws Exception {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      return rethrow(e.getCause());
    }
  }

  /** 音声による対話で用いるrethrowを返す。 */
  private static Object rethrow(Throwable cause) throws Exception {
    if (cause instanceof Exception e) throw e;
    if (cause instanceof Error e) throw e;
    throw new IllegalStateException(cause);
  }

  /** 後続の処理が利用できるよう、Sherpaバックエンド生成元を解放する。 */
  private void release(Object target) {
    try {
      call(target, "release");
    } catch (Exception e) {
      throw new IllegalStateException("Native release failed", e);
    }
  }

  /** 音声による対話に関する利用者解放済みを処理する。 */
  private synchronized void userReleased() {
    users--;
    if (closing && users == 0) closeLoader();
  }

  /** 使い終わった資源や状態を解放するため、読み込みの窓口の利用を終える。 */
  private void closeLoader() {
    if (loader != null)
      try {
        loader.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public synchronized void close() {
    closing = true;
    if (users == 0) closeLoader();
  }
}
