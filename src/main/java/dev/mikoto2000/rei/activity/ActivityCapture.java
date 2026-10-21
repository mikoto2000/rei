package dev.mikoto2000.rei.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作業状況の把握に必要な作業状況取得記録を扱う。
 *
 * <p>Observation and inference have separate workers. Only the latest pending image is retained in
 * memory.
 */
public final class ActivityCapture implements AutoCloseable {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ActivityCapture.class);

  /** この機能の動作設定。 */
  private final ActivityProperties properties;

  /** 観測。 */
  private final DesktopActivityObserver observer;

  /** 情報の抽出。 */
  private final ActivityExtractor extractor;

  /** 保存先。 */
  private final ActivityStore store;

  /** 画面画像一覧。 */
  private final ScreenshotStore screenshots;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 根拠処理の連携。 */
  private final ActivityEvidencePipeline evidencePipeline;

  /** 観測内容。 */
  private final InputAwareObservation observation;

  /** 分析実行先。 */
  private final java.util.concurrent.Executor analysisExecutor;

  /** 実行中。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 分析中かどうか。 */
  private boolean analyzing;

  /** 待機中。 */
  private Frame pending;

  /** 作業状況の把握に必要な観測単位を扱う。 */
  private static final class Frame {
    /** トークン／待機中日時。 */
    final long token, queuedAt;

    /** 日時。 */
    final Instant at;

    /** 前面。 */
    final ForegroundWindow foreground;

    /** 画面。 */
    final dev.mikoto2000.rei.computeruse.CapturedScreen screen;

    /** 指定された値や利用先を使って、Frameを初期化する。 */
    Frame(
        long token,
        Instant at,
        ForegroundWindow foreground,
        dev.mikoto2000.rei.computeruse.CapturedScreen screen,
        long queuedAt) {
      this.token = token;
      this.at = at;
      this.foreground = foreground;
      this.screen = screen;
      this.queuedAt = queuedAt;
    }

    /** 作業状況の把握で用いるトークンを返す。 */
    long token() {
      return token;
    }

    /** 作業状況の把握で用いる待機中日時を返す。 */
    long queuedAt() {
      return queuedAt;
    }

    /** 作業状況の把握で用いる日時を返す。 */
    Instant at() {
      return at;
    }

    /** 作業状況の把握で用いる前面を返す。 */
    ForegroundWindow foreground() {
      return foreground;
    }

    /** 作業状況の把握で用いる画面を返す。 */
    dev.mikoto2000.rei.computeruse.CapturedScreen screen() {
      return screen;
    }
  }

  /** 一時停止中／終了済み。 */
  private boolean paused, closed;

  /** 生成。 */
  private long generation;

  /** 作業の継続を識別する値。 */
  private String continuityId = UUID.randomUUID().toString();

  /** 前回。 */
  private ActivityRecord previous;

  /** 内容の同一性を判断する情報。 */
  private Map<String, double[]> fingerprints = Map.of();

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityCapture(
      ActivityProperties properties,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      Clock clock) {
    this(properties, observer, extractor, store, screenshots, clock, Runnable::run);
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityCapture(
      ActivityProperties properties,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      Clock clock,
      java.util.concurrent.Executor analysisExecutor) {
    this(
        properties,
        observer,
        extractor,
        store,
        screenshots,
        clock,
        analysisExecutor,
        Runnable::run);
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityCapture(
      ActivityProperties properties,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      Clock clock,
      java.util.concurrent.Executor analysisExecutor,
      java.util.concurrent.Executor backgroundExecutor) {
    this(
        properties,
        observer,
        extractor,
        store,
        screenshots,
        clock,
        analysisExecutor,
        backgroundExecutor,
        List.of());
  }

  /** 指定された値や利用先を使って、ActivityCaptureを初期化する。 */
  public ActivityCapture(
      ActivityProperties properties,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      Clock clock,
      java.util.concurrent.Executor analysisExecutor,
      java.util.concurrent.Executor backgroundExecutor,
      List<ActivityEvidenceSource> sources) {
    properties.validate();
    this.properties = properties;
    this.observer = observer;
    this.extractor = extractor;
    this.store = store;
    this.screenshots = screenshots;
    this.clock = clock;
    this.analysisExecutor = analysisExecutor;
    this.observation =
        new InputAwareObservation(
            clock,
            properties.getObservation().getIntervalSeconds(),
            properties.getObservation().getMaxObservationIntervalSeconds());
    this.evidencePipeline =
        new ActivityEvidencePipeline(
            properties,
            observer,
            extractor,
            store,
            screenshots,
            clock,
            analysisExecutor,
            backgroundExecutor,
            sources);
    this.evidencePipeline.observation = observation;
  }

  /** 作業状況の把握に関する一時停止を処理する。 */
  public synchronized void pause() {
    paused = true;
    observation.force();
    invalidate();
    evidencePipeline.pause();
  }

  /** 作業状況の把握に関する利用補助操作を処理する。 */
  public void useToolkit(ClassificationToolkit toolkit) {
    evidencePipeline.useToolkit(toolkit);
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public synchronized void resume() {
    paused = false;
    observation.force();
    invalidate();
    evidencePipeline.resume();
  }

  /** 作業状況の把握において、一時停止中かどうかを判定する。 */
  public synchronized boolean isPaused() {
    return paused;
  }

  /** 作業状況の把握に関する強制観測内容を処理する。 */
  public void forceObservation() {
    observation.force();
  }

  /** 作業状況の把握で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public synchronized void close() {
    closed = true;
    pause();
    evidencePipeline.close();
  }

  /** 作業状況の把握において、許可に該当するか判定する。 */
  private synchronized boolean allowed(long token) {
    return properties.isEnabled() && !paused && !closed && token == generation;
  }

  /** 作業状況の把握に関する定期確認を処理する。 */
  public void tick() {
    try {
      observationTick();
    } finally {
      evidencePipeline.pollForeground();
    }
  }

  /** 作業状況の把握に関する観測内容定期確認を処理する。 */
  private void observationTick() {
    if (!properties.getObservation().isInputAwareEnabled()) {
      detailTick(false);
      return;
    }
    boolean active;
    synchronized (this) {
      active = allowed(generation);
    }
    if (!active) {
      detailTick(false);
      return;
    }
    long probeStarted = System.nanoTime();
    var sample = lightweight();
    boolean detailed = observation.begin(sample);
    log.debug(
        "Activity lightweight observation: probe_us={} reliable={} detailed={}",
        java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - probeStarted),
        sample != null && sample.reliable(),
        detailed);
    if (!detailed) return;
    try {
      detailTick(sample != null);
    } finally {
      observation.end(lightweight());
    }
  }

  /** 作業状況の把握で用いるlightweightを返す。 */
  private DesktopActivityObserver.Lightweight lightweight() {
    try {
      return observer.lightweight();
    } catch (Exception | LinkageError e) {
      return new DesktopActivityObserver.Lightweight(0, 0, null, null, false, false);
    }
  }

  /** 作業状況の把握に関する詳細定期確認を処理する。 */
  private void detailTick(boolean optimized) {
    if (optimized
        || properties.getDetection().isBackgroundFullScreenEnabled()
        || properties.getDetection().getMode() == ActivityProperties.DetectionMode.EVIDENCE_FIRST) {
      evidencePipeline.tick();
      return;
    }
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!running.compareAndSet(false, true)) return;
    long started = System.nanoTime();
    String status = "skipped";
    try {
      try {
        screenshots.cleanup(
            clock.instant().minus(Duration.ofDays(properties.getScreenshotRetentionDays())));
      } catch (Exception e) {
        failure("retention", e);
      }
      long token;
      synchronized (this) {
        token = generation;
      }
      if (!allowed(token)) return;
      var foreground = observer.foreground();
      if (new CapturePolicy(properties).excluded(foreground)) {
        invalidate();
        return;
      }
      dev.mikoto2000.rei.computeruse.CapturedScreen screen;
      Instant at;
      synchronized (this) {
        if (!allowed(token)) return;
        at = clock.instant();
        screen = observer.capture();
      }
      if (!allowed(token)) return;
      if (!Objects.equals(foreground, observer.foreground())) {
        invalidate();
        return;
      }
      long observed = System.nanoTime();
      log.info(
          "Activity observation timing: observe_ms={} displays={}",
          ms(observed - started),
          screen.displays().size());
      var frame = new Frame(token, at, foreground, screen, observed);
      submit(frame);
      status = "submitted";
    } catch (Exception e) {
      status = "failed";
      invalidate();
      failure("observation", e);
    } finally {
      if (!status.equals("submitted"))
        log.debug(
            "Activity observation: status={} elapsed_ms={}",
            status,
            ms(System.nanoTime() - started));
      running.set(false);
    }
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  private void submit(Frame frame) {
    synchronized (this) {
      if (!allowed(frame.token())) return;
      if (pending != null)
        log.info(
            "Activity analysis backlog: replaced_pending=1 age_ms={}",
            ms(System.nanoTime() - pending.queuedAt()));
      pending = frame;
      if (analyzing) return;
      analyzing = true;
    }
    try {
      analysisExecutor.execute(this::drain);
    } catch (RuntimeException e) {
      synchronized (this) {
        analyzing = false;
        pending = null;
      }
      failure("analysis scheduling", e);
    }
  }

  /** 作業状況の把握に関する残っている対象の処理を処理する。 */
  private void drain() {
    while (true) {
      Frame frame;
      synchronized (this) {
        frame = pending;
        pending = null;
        if (frame == null) {
          analyzing = false;
          return;
        }
      }
      analyze(frame);
    }
  }

  /** 作業状況の把握に関する分析を処理する。 */
  private void analyze(Frame frame) {
    long token = frame.token();
    var at = frame.at();
    var foreground = frame.foreground();
    var screen = frame.screen();
    if (!allowed(token)) return;
    long started = System.nanoTime();
    try {
      var observations =
          screen.displays().stream()
              .map(
                  d -> {
                    var b = d.geometry().bounds();
                    return new ActivityRecord.Observation(
                        d.geometry().id(),
                        new ActivityRecord.Bounds(b.x, b.y, b.width, b.height),
                        at);
                  })
              .toList();
      var front =
          properties.getDetection().isForegroundCrop()
              ? ActivityImages.foreground(screen, foreground)
              : screen;
      var current = fingerprints(front);
      ActivityRecord prior;
      Map<String, double[]> baseline;
      synchronized (this) {
        prior = previous;
        baseline = fingerprints;
      }
      double change = prior == null ? 1 : distance(current, baseline);
      boolean duplicate =
          prior != null
              && Objects.equals(prior.foreground(), foreground)
              && sameGeometry(prior.observations(), observations)
              && change <= properties.getChangeThreshold()
              && Duration.between(prior.capturedAt(), at).getSeconds()
                  <= Math.max(
                      properties.getSessionGapSeconds(),
                      properties.getCaptureIntervalSeconds() * 2L);
      if (!allowed(token)) return;
      log.info(
          "Activity analysis timing: queue_wait_ms={} change_check_ms={} scope={} duplicate={}",
          ms(started - frame.queuedAt()),
          ms(System.nanoTime() - started),
          front == screen ? "desktop_fallback" : "foreground",
          duplicate);
      ActivityExtractor.Result result;
      try {
        result =
            duplicate
                ? new ActivityExtractor.Result(prior.inference(), prior.confidence())
                : properties.isExtractionEnabled() && properties.getDetection().isVisionEnabled()
                    ? extract(front, foreground, "foreground")
                    : new ActivityExtractor.Result(
                        new ActivityRecord.Inference(
                            "OS observation only; activity unknown", List.of()),
                        0);
      } catch (Exception error) {
        saveEvidence(
            token,
            UUID.randomUUID().toString(),
            at,
            screen,
            duplicate,
            ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE);
        synchronized (this) {
          if (allowed(token)) resetEvidence();
        }
        failure("extraction", error);
        return;
      }
      synchronized (this) {
        if (!allowed(token)) return;
        long saveStarted = System.nanoTime();
        var id = UUID.randomUUID().toString();
        var refs =
            duplicate
                ? prior.screenshotReferences()
                : saveEvidence(
                    token, id, at, screen, false, ScreenshotPersistencePolicy.Outcome.SUCCESS);
        var record =
            new ActivityRecord(
                id,
                at,
                properties.getCaptureIntervalSeconds(),
                observations,
                foreground,
                result.inference(),
                result.confidence(),
                refs,
                change,
                duplicate,
                continuityId);
        store.append(record);
        log.info(
            "Activity record saved: storage_ms={} observation_age_ms={} duplicate={}",
            ms(System.nanoTime() - saveStarted),
            Duration.between(at, clock.instant()).toMillis(),
            duplicate);
        previous = record;
        // Compare to the last analyzed image, not the last sample: slow cumulative changes still
        // trigger extraction.
        // Keep foreground-only evidence in the reuse cache, even after background supplementation.
        if (!duplicate) fingerprints = Map.copyOf(current);
      }
    } catch (Exception e) {
      synchronized (this) {
        if (allowed(token)) resetEvidence();
      }
      failure("extraction/storage", e);
    }
  }

  /** 後から参照できるよう、根拠を保存する。 */
  private synchronized List<String> saveEvidence(
      long token,
      String id,
      Instant at,
      dev.mikoto2000.rei.computeruse.CapturedScreen screen,
      boolean duplicate,
      ScreenshotPersistencePolicy.Outcome outcome) {
    // Called only after the foreground privacy checks. A pause/close invalidates in-flight failures
    // too.
    if (!allowed(token)
        || !new ScreenshotPersistencePolicy(properties).shouldSave(duplicate, outcome))
      return List.of();
    try {
      return screenshots.save(id, at, screen);
    } catch (Exception error) {
      failure("evidence persistence", error);
      return List.of(); // A failed optional evidence write must not discard a valid ActivityRecord.
    }
  }

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  private ActivityExtractor.Result extract(
      dev.mikoto2000.rei.computeruse.CapturedScreen screen,
      ForegroundWindow foreground,
      String scope)
      throws Exception {
    try (var ignored = org.slf4j.MDC.putCloseable("activityScope", scope)) {
      return extractor.extract(screen, foreground);
    }
  }

  /** 作業状況の把握で用いるfingerprintsを返す。 */
  private static Map<String, double[]> fingerprints(
      dev.mikoto2000.rei.computeruse.CapturedScreen screen) {
    var values = new HashMap<String, double[]>();
    screen
        .displays()
        .forEach(d -> values.put(d.geometry().id(), ImageChange.fingerprint(d.image())));
    return Map.copyOf(values);
  }

  /** 作業状況の把握で用いる距離を返す。 */
  private static double distance(Map<String, double[]> current, Map<String, double[]> baseline) {
    return current.keySet().equals(baseline.keySet())
        ? current.entrySet().stream()
            .mapToDouble(e -> ImageChange.distance(e.getValue(), baseline.get(e.getKey())))
            .max()
            .orElse(1)
        : 1;
  }

  /** 新たな処理を開始できるよう、根拠を初期状態へ戻す。 */
  private synchronized void resetEvidence() {
    previous = null;
    fingerprints = Map.of();
    continuityId = UUID.randomUUID().toString();
  }

  /** 作業状況の把握に関するキャッシュの無効化を処理する。 */
  private synchronized void invalidate() {
    generation++;
    pending = null;
    resetEvidence();
  }

  /** 作業状況の把握で用いるミリ秒を返す。 */
  private static long ms(long nanos) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  /** 作業状況の把握において、同一画面上の位置と大きさに該当するか判定する。 */
  private static boolean sameGeometry(
      List<ActivityRecord.Observation> a, List<ActivityRecord.Observation> b) {
    return a.stream()
        .map(o -> Map.entry(o.monitor(), o.bounds()))
        .toList()
        .equals(b.stream().map(o -> Map.entry(o.monitor(), o.bounds())).toList());
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  private static void failure(String stage, Exception error) {
    // Never log window titles, screenshot content, provider payloads or parser source text.
    if (error instanceof ActivityOutputParser.InvalidOutput invalid)
      log.warn("Activity {} failed (InvalidOutput): {}", stage, invalid.diagnostic());
    else log.warn("Activity {} failed ({})", stage, error.getClass().getSimpleName());
  }
}
