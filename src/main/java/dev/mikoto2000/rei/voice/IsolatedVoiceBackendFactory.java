package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 独立した音声バックエンドの情報を共通の形式で生成する。
 *
 * <p>Separate VAD/ASR workers keep recognition out of capture's path and native failure out of the
 * CLI.
 */
public final class IsolatedVoiceBackendFactory implements VoiceBackendFactory, AutoCloseable {
  /** ディレクトリ。 */
  private final Supplier<Path> directory;

  /** 推定。 */
  private final Supplier<VoiceInferenceOptions> inference;

  /** 実行の担当一覧。 */
  private final Set<VoiceWorkerProcess> workers = new HashSet<>();

  /** 終了済み。 */
  private boolean closed;

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public IsolatedVoiceBackendFactory(Supplier<Path> directory) {
    this(directory, VoiceInferenceOptions::defaults);
  }

  /** 指定された値や利用先を使って、IsolatedVoiceBackendFactoryを初期化する。 */
  public IsolatedVoiceBackendFactory(
      Supplier<Path> directory, Supplier<VoiceInferenceOptions> inference) {
    this.directory = Objects.requireNonNull(directory);
    this.inference = Objects.requireNonNull(inference);
  }

  /** 要求された処理を開始する。 */
  private VoiceWorkerProcess start(
      String role, Path root, VoiceSettings settings, VoiceInferenceOptions options)
      throws Exception {
    var arguments = new ArrayList<String>();
    arguments.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
    arguments.add("--enable-native-access=ALL-UNNAMED");
    arguments.add("-XX:-CreateCoredumpOnCrash");
    arguments.add("-XX:ErrorFile=NUL");
    var location = NativeVoiceWorker.class.getProtectionDomain().getCodeSource().getLocation();
    if (location.getProtocol().equals("file")) {
      arguments.add("-cp");
      arguments.add(Path.of(location.toURI()).toString());
      arguments.add(NativeVoiceWorker.class.getName());
    } else {
      arguments.add("-Dloader.main=" + NativeVoiceWorker.class.getName());
      arguments.add("-cp");
      arguments.add(System.getProperty("java.class.path"));
      arguments.add("org.springframework.boot.loader.launch.PropertiesLauncher");
    }
    arguments.addAll(
        List.of(
            role,
            root.toString(),
            Float.toString(settings.threshold()),
            Integer.toString(settings.preRollMs()),
            Integer.toString(settings.minSpeechMs()),
            Integer.toString(settings.silenceMs()),
            Integer.toString(settings.maxSpeechMs()),
            Integer.toString(settings.tailMs()),
            Integer.toString(options.threads()),
            Integer.toString(options.tailFrames())));
    var worker =
        VoiceWorkerProcess.start(
            arguments,
            role.equals("vad") ? VoiceRuntimeLimits.VAD_STARTUP : VoiceRuntimeLimits.ASR_STARTUP,
            role.equals("vad") ? VoiceRuntimeLimits.VAD_REQUEST : VoiceRuntimeLimits.ASR_REQUEST);
    synchronized (this) {
      if (closed) {
        worker.close();
        throw new IOException("Voice worker factory closed");
      }
      workers.add(worker);
    }
    return worker;
  }

  /** 後続の処理が利用できるよう、独立した音声バックエンド生成元を解放する。 */
  private void release(VoiceWorkerProcess worker) {
    try {
      worker.close();
    } finally {
      synchronized (this) {
        if (worker.isAlive()) throw new IllegalStateException("Owned voice worker did not exit");
        workers.remove(worker);
      }
    }
  }

  /** 必要な対象を利用できるよう、独立した音声バックエンド生成元の利用を開始する。 */
  public VoiceBackend open(VoiceSettings settings) throws Exception {
    synchronized (this) {
      if (closed) throw new IOException("Voice worker factory closed");
    }
    Path root = directory.get().toAbsolutePath().normalize();
    var options = Objects.requireNonNull(inference.get());
    var vad = start("vad", root, settings, options);
    VoiceWorkerProcess recognizer = null;
    try {
      recognizer = start("asr", root, settings, options);
      var decoder = recognizer;
      return new VoiceBackend(
          new VoiceActivityDetector() {
            /** 音声による対話で用いるprobabilityを返す。 */
            public float probability(float[] frame) throws Exception {
              return vad.probability(frame);
            }

            /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
            public void close() {
              release(vad);
            }
          },
          new SpeechRecognizer() {
            /** 音声による対話で用いるrecognizeを返す。 */
            public String recognize(SpeechSegment segment) throws Exception {
              return decoder.recognize(segment.samples());
            }

            /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
            public void close() {
              release(decoder);
            }
          });
    } catch (Exception | LinkageError failure) {
      if (recognizer != null) release(recognizer);
      release(vad);
      throw failure;
    }
  }

  /** 音声による対話で用いるlive実行の担当一覧を返す。 */
  public synchronized long liveWorkers() {
    return workers.stream().filter(VoiceWorkerProcess::isAlive).count();
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    List<VoiceWorkerProcess> owned;
    synchronized (this) {
      closed = true;
      owned = List.copyOf(workers);
    }
    RuntimeException failure = null;
    for (var worker : owned) {
      try {
        release(worker);
      } catch (RuntimeException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }
}
