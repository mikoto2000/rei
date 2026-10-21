package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 音声モデルの状態と操作を管理する。
 *
 * <p>One asynchronous, explicitly approved transaction. Only a verified whole directory is
 * activated.
 */
public final class VoiceModelManager implements AutoCloseable {
  /** 音声による対話における状態の選択肢。 */
  public enum State {
    /** 未指定。 */
    MISSING,
    /** 破損。 */
    CORRUPT,
    /** モデルを取得している状態。 */
    DOWNLOADING,
    /** 結果を確認中の状態。 */
    VERIFYING,
    /** 準備完了。 */
    READY,
    /** 失敗。 */
    FAILED,
    /** キャンセル済み。 */
    CANCELLED,
    /** 終了済み。 */
    CLOSED
  }

  /**
   * 音声による対話で受け渡す状態を表す。
   *
   * @param state 状態
   * @param bytes バイト数
   * @param totalBytes 合計バイト数
   * @param asset 素材
   * @param attempt 試行
   * @param failure 失敗情報
   */
  public record Status(
      State state, long bytes, long totalBytes, String asset, int attempt, String failure) {}

  /** 取得タイムアウト。 */
  static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(1);

  /** DISK予約バイト数。 */
  static final long DISK_RESERVE_BYTES = 64L * 1024 * 1024;

  /** 音声による対話に必要な利用可能Spaceを扱う。 */
  @FunctionalInterface
  interface AvailableSpace {
    /** 音声による対話で用いるバイト数を返す。 */
    long bytes(Path path) throws IOException;
  }

  /** 音声による対話を継続できないInsufficient保存の状況を伝える例外。 */
  private static final class InsufficientStorageException extends IOException {
    /** 指定された値や利用先を使って、InsufficientStorageExceptionを初期化する。 */
    InsufficientStorageException(long required) {
      super("insufficient free space; requires " + required + " bytes including reserve");
    }
  }

  /** 利用可能Space。 */
  private final AvailableSpace availableSpace;

  /** 作業ルート。 */
  private final Path root;

  /** 音声モデルの構成と入手先の情報。 */
  private final VoiceModelManifest manifest;

  /** 通信経路。 */
  private final VoiceAssetTransport transport;

  /** 通知の受け取り先。 */
  private final Consumer<Status> listener;

  /** 実行の担当。 */
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "voice-model-download");
            t.setDaemon(true);
            return t;
          });

  /** 期限一覧。 */
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "voice-model-deadline");
            t.setDaemon(true);
            return t;
          });

  /** 状態。 */
  private volatile Status status;

  /** 使用中。 */
  private volatile boolean busy;

  /** 終了済み。 */
  private boolean closed;

  /** キャンセル。 */
  private VoiceAssetTransport.Cancellation cancellation;

  /** 所有者。 */
  private volatile Thread owner;

  /** 直近進捗。 */
  private long lastProgress;

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceModelManager(
      Path root,
      VoiceModelManifest manifest,
      VoiceAssetTransport transport,
      Consumer<Status> listener) {
    this(root, manifest, transport, listener, path -> Files.getFileStore(path).getUsableSpace());
  }

  /** 指定された値や利用先を使って、VoiceModelManagerを初期化する。 */
  VoiceModelManager(
      Path root,
      VoiceModelManifest manifest,
      VoiceAssetTransport transport,
      Consumer<Status> listener,
      AvailableSpace availableSpace) {
    this.availableSpace = Objects.requireNonNull(availableSpace);
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    this.manifest = Objects.requireNonNull(manifest);
    this.transport = Objects.requireNonNull(transport);
    this.listener = Objects.requireNonNull(listener);
    status = new Status(State.MISSING, 0, manifest.totalBytes(), "", 0, "");
  }

  /** 音声による対話で用いるmanifestを返す。 */
  public VoiceModelManifest manifest() {
    return manifest;
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public Status status() {
    return status;
  }

  /** 音声による対話において、使用中に該当するか判定する。 */
  public boolean busy() {
    return busy;
  }

  /**
   * 音声による対話で用いる準備完了ディレクトリを返す。
   *
   * <p>Hashes are checked on every reuse, including manually placed bundles, without loading JNI.
   */
  public Path readyDirectory() throws IOException {
    Path managed = VoiceModelManifest.assetPath(root, "managed/" + manifest.id());
    try {
      manifest.verify(managed);
      return verifiedDirectory(managed);
    } catch (InterruptedIOException interrupted) {
      throw interrupted;
    } catch (IOException invalidManaged) {
      try {
        manifest.verify(root);
        return verifiedDirectory(root);
      } catch (InterruptedIOException interrupted) {
        throw interrupted;
      } catch (IOException invalidManual) {
        synchronized (this) {
          if (!busy
              && !closed
              && (status.state() == State.READY
                  || status.state() == State.MISSING
                  || status.state() == State.CORRUPT)) {
            boolean partial =
                Files.exists(managed, LinkOption.NOFOLLOW_LINKS)
                    || manifest.assets().stream()
                        .anyMatch(
                            a -> Files.exists(root.resolve(a.path()), LinkOption.NOFOLLOW_LINKS));
            update(
                partial ? State.CORRUPT : State.MISSING,
                0,
                "",
                0,
                partial ? "cached bundle failed integrity" : "");
          }
        }
        throw new IOException(
            "Voice bundle missing or corrupt; use /voice models info and install", invalidManual);
      }
    }
  }

  /** 音声による対話で用いる確認済みディレクトリを返す。 */
  private synchronized Path verifiedDirectory(Path directory) {
    if (!busy && !closed && status.state() != State.READY)
      update(State.READY, manifest.totalBytes(), "", 0, "");
    return directory;
  }

  /** 音声による対話において、installに該当するか判定する。 */
  public synchronized boolean install(String approvedManifestId) {
    if (!manifest.id().equals(approvedManifestId))
      throw new IllegalArgumentException(
          "Approve the displayed manifest ID with /voice models install --approve ID");
    if (closed) throw new IllegalStateException("Voice model manager closed");
    if (busy) throw new IllegalStateException("Voice download already active");
    try {
      readyDirectory();
      update(State.READY, manifest.totalBytes(), "", 0, "");
      return false;
    } catch (InterruptedIOException interrupted) {
      throw new IllegalStateException("Voice integrity verification interrupted", interrupted);
    } catch (IOException unavailable) {
    }
    cancellation = new VoiceAssetTransport.Cancellation();
    busy = true;
    update(State.DOWNLOADING, 0, "", 0, "");
    var token = cancellation;
    worker.execute(() -> download(token));
    return true;
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private void update(State state, long bytes, String asset, int attempt, String failure) {
    status = new Status(state, bytes, manifest.totalBytes(), asset, attempt, failure);
    long now = System.nanoTime();
    if (state != State.DOWNLOADING
        || bytes == 0
        || bytes == manifest.totalBytes()
        || now - lastProgress > TimeUnit.SECONDS.toNanos(1)) {
      lastProgress = now;
      try {
        listener.accept(status);
      } catch (RuntimeException ignored) {
      }
    }
  }

  /** 対象の実行をキャンセルする。 */
  public boolean cancel() {
    VoiceAssetTransport.Cancellation token;
    Thread thread;
    synchronized (this) {
      if (!busy || status.state() == State.READY) return false;
      token = cancellation;
      token.cancel();
      thread = owner;
    }
    token.cancel();
    if (thread != null) thread.interrupt();
    return true;
  }

  /** 音声による対話に関する取得を処理する。 */
  private void download(VoiceAssetTransport.Cancellation token) {
    Path stage = null;
    State result = State.FAILED;
    String failure = "download failed";
    long completed = 0;
    owner = Thread.currentThread();
    ScheduledFuture<?> timeout = null;
    try {
      token.check();
      timeout =
          deadlines.schedule(this::cancel, DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      VoiceModelManifest.assetPath(root, "staging/safety-check");
      Files.createDirectories(root.resolve("staging"));
      stage = Files.createTempDirectory(root.resolve("staging"), "download-");
      for (var asset : manifest.assets()) {
        long required = manifest.totalBytes() - completed + DISK_RESERVE_BYTES;
        if (availableSpace.bytes(stage) < required)
          throw new InsufficientStorageException(required);
        token.check();
        Path target = VoiceModelManifest.assetPath(stage, asset.path());
        Files.createDirectories(target.getParent());
        IOException last = null;
        final long prior = completed;
        for (int attempt = 1; attempt <= 3; attempt++) {
          token.check();
          final int number = attempt;
          update(State.DOWNLOADING, prior, asset.path(), attempt, "");
          try {
            transport.download(
                asset,
                target,
                token,
                count ->
                    update(
                        State.DOWNLOADING,
                        prior + Math.min(count, asset.bytes()),
                        asset.path(),
                        number,
                        ""));
            last = null;
            break;
          } catch (IOException error) {
            token.check();
            last = error;
            Files.deleteIfExists(target);
            if (attempt < 3) Thread.sleep(250L * attempt);
          }
        }
        if (last != null) throw last;
        VoiceModelManifest.verifyAsset(asset, target);
        completed += asset.bytes();
      }
      token.check();
      update(State.VERIFYING, completed, "", 0, "");
      manifest.verify(stage);
      synchronized (this) {
        token.check();
        if (closed) throw new InterruptedIOException("Voice manager closed");
        Path active = VoiceModelManifest.assetPath(root, "managed/" + manifest.id());
        Files.createDirectories(active.getParent());
        Path backup = null;
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
          backup =
              VoiceModelManifest.assetPath(
                  root, "retained/" + manifest.id() + "-" + UUID.randomUUID());
          Files.createDirectories(backup.getParent());
          Files.move(active, backup, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
          Files.move(stage, active, StandardCopyOption.ATOMIC_MOVE);
          stage = null;
        } catch (IOException error) {
          if (backup != null && !Files.exists(active))
            Files.move(backup, active, StandardCopyOption.ATOMIC_MOVE);
          throw error;
        }
        result = State.READY;
        failure = "";
        update(State.READY, completed, "", 0, "");
      }
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      result = State.CANCELLED;
      failure = "cancelled";
    } catch (Exception error) {
      result = token.cancelled() ? State.CANCELLED : State.FAILED;
      failure =
          token.cancelled()
              ? "cancelled"
              : error instanceof InsufficientStorageException
                  ? error.getMessage()
                  : "download, size, integrity or activation failed";
    } finally {
      if (timeout != null) timeout.cancel(false);
      token.cancel();
      if (stage != null)
        try {
          deleteStage(stage);
        } catch (IOException error) {
          failure = "staging cleanup failed";
          if (result != State.CANCELLED) result = State.FAILED;
        }
      synchronized (this) {
        owner = null;
        update(closed ? State.CLOSED : result, completed, "", 0, failure);
        busy = false;
        cancellation = null;
        notifyAll();
      }
      Thread.interrupted();
    }
  }

  /** 対象を管理から外すため、段階を削除する。 */
  private void deleteStage(Path stage) throws IOException {
    Path staging = root.resolve("staging").normalize();
    Path checked = stage.toAbsolutePath().normalize();
    if (!checked.startsWith(staging) || checked.equals(staging))
      throw new IOException("Unsafe staging cleanup");
    try (var paths = Files.walk(checked)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    cancel();
    deadlines.shutdownNow();
    worker.shutdown();
    try {
      worker.awaitTermination(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
    synchronized (this) {
      if (!busy) update(State.CLOSED, status.bytes(), "", 0, "");
    }
  }
}
