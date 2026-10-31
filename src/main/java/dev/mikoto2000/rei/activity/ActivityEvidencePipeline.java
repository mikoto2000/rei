package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 作業状況の把握に必要な作業状況根拠処理の連携を扱う。
 *
 * <p>Persist metadata first. Bounded image workers only enrich already-persisted observations.
 */
final class ActivityEvidencePipeline {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ActivityEvidencePipeline.class);

  /** この機能の動作設定。 */
  private final ActivityProperties p;

  /** 観測内容。 */
  InputAwareObservation observation;

  volatile dev.mikoto2000.rei.life.LifePolicy life;

  private boolean lifeAllowed(Instant since) {
    return life == null || life.observationAllowed(since);
  }

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

  /** 前面実行先。 */
  private final Executor foregroundExecutor;

  /** 集計。 */
  private final ActivityEvidenceAggregator aggregator;

  /** 分類。 */
  private java.util.function.Function<ActivityEvidence, ActivityClassification> classify =
      new ActivityClassifier()::classify;

  /** 作業状況の把握に関する利用補助操作を処理する。 */
  void useToolkit(ClassificationToolkit toolkit) {
    classify = toolkit::classify;
  }

  /** 観測中かどうか。 */
  private final AtomicBoolean observing = new AtomicBoolean();

  /** 観測内容一覧／根拠限定／fallbacks／対象外／前面呼び出し回数／バックグラウンド呼び出し回数／成功／失敗情報／タイムアウト。 */
  private final LongAdder observations = new LongAdder(),
      evidenceOnly = new LongAdder(),
      fallbacks = new LongAdder(),
      skipped = new LongAdder(),
      foregroundCalls = new LongAdder(),
      backgroundCalls = new LongAdder(),
      success = new LongAdder(),
      failure = new LongAdder(),
      timeout = new LongAdder();

  /** 出力上限一覧／検証失敗一覧／不明件数／部分的な結果件数／利用可能件数。 */
  private final LongAdder outputLimits = new LongAdder(),
      validationFailures = new LongAdder(),
      unknownCount = new LongAdder(),
      partialCount = new LongAdder(),
      usableCount = new LongAdder();

  /** 一時停止中／終了済み／前面使用中。 */
  private boolean paused, closed, foregroundBusy;

  /** 生成。 */
  private long generation;

  /** 継続性。 */
  private String continuity = UUID.randomUUID().toString();

  /** 前回。 */
  private ActivityRecord previous;

  /** 待機中／デスクトップ待機中。 */
  private Work pending, desktopPending;

  /** デスクトップ判断基準。 */
  private final DesktopAnalysisPolicy desktopPolicy;

  /** 作業状況の把握で用いるデスクトップ待機中を返す。 */
  synchronized int desktopPending() {
    return desktopPending == null ? 0 : 1;
  }

  /** 前面開始／直近成功日時。 */
  private Instant foregroundStarted, lastSucceededAt;

  /** 直近成功キー。 */
  private CandidateKey lastSucceededKey;

  /** 直近成功結果。 */
  private ActivityExtractor.Result lastSucceededResult;

  /** 直近成功時間計測。 */
  private VisionDiagnostics.Timing lastSucceededTiming;

  /** 生成済み／完了／失敗／置換済み／重複の有無対象外／後で扱う対象。 */
  private final LongAdder generated = new LongAdder(),
      completed = new LongAdder(),
      failed = new LongAdder(),
      replaced = new LongAdder(),
      duplicateSkipped = new LongAdder(),
      deferred = new LongAdder();

  /** 実行待ち待機ミリ秒数／実行ミリ秒数／終了終点終了ミリ秒数。 */
  private final LongAdder queueWaitMillis = new LongAdder(),
      executionMillis = new LongAdder(),
      endToEndMillis = new LongAdder();

  /**
   * 作業状況の把握で受け渡す実行待ち計測値を表す。
   *
   * @param generated 生成済み
   * @param started 開始
   * @param completed 完了
   * @param failed 失敗
   * @param replaced 置換済み
   * @param duplicateSkipped 重複の有無対象外
   * @param deferred 後で扱う対象
   * @param apiCalls API呼び出し回数
   * @param queueWaitMillis 実行待ち待機ミリ秒数
   * @param executionMillis 実行ミリ秒数
   * @param endToEndMillis 終了終点終了ミリ秒数
   * @param pending 待機中
   * @param running 実行中
   */
  record QueueMetrics(
      long generated,
      long started,
      long completed,
      long failed,
      long replaced,
      long duplicateSkipped,
      long deferred,
      long apiCalls,
      long queueWaitMillis,
      long executionMillis,
      long endToEndMillis,
      int pending,
      boolean running) {}

  /** 作業状況の把握で用いる実行待ち計測値を返す。 */
  synchronized QueueMetrics queueMetrics() {
    return new QueueMetrics(
        generated.sum(),
        foregroundCalls.sum(),
        completed.sum(),
        failed.sum(),
        replaced.sum(),
        duplicateSkipped.sum(),
        deferred.sum(),
        foregroundCalls.sum(),
        queueWaitMillis.sum(),
        executionMillis.sum(),
        endToEndMillis.sum(),
        pending == null ? 0 : 1,
        foregroundBusy);
  }

  /**
   * 作業状況の把握で受け渡す候補キーを表す。
   *
   * @param foreground 前面
   * @param projectId 所属プロジェクトの識別子
   * @param projectName プロジェクト名前
   * @param branch ブランチ
   * @param commit 確定
   * @param revision 改訂番号
   */
  private record CandidateKey(
      ForegroundWindow foreground,
      String projectId,
      String projectName,
      String branch,
      String commit,
      long revision) {
    /** 作業状況の把握で受け渡す情報を、指定された内容から作成する。 */
    static CandidateKey of(ActivityRecord record) {
      return of(record.detection().evidence());
    }

    /** 作業状況の把握で受け渡す情報を、指定された内容から作成する。 */
    static CandidateKey of(ActivityEvidence e) {
      var w = e.workContext();
      var git = w == null ? null : w.git();
      return new CandidateKey(
          e.foreground(),
          e.projectId(),
          e.projectName(),
          git == null ? null : git.branch(),
          git == null ? null : git.commit(),
          w == null ? 0 : w.revision());
    }
  }

  /** 作業状況の把握に必要な作業を扱う。 */
  private static final class Work {
    /** 生成。 */
    final long generation;

    final dev.mikoto2000.rei.life.LifeContext.Lease lifeLease;

    /** 画面。 */
    final CapturedScreen screen;

    /** 画像観測日時。 */
    final Instant imageCapturedAt;

    /** 照合キー。 */
    final CandidateKey key;

    /** デスクトップ。 */
    CapturedScreen desktop;

    /** 記録。 */
    ActivityRecord record;

    /** 指定された値や利用先を使って、Workを初期化する。 */
    Work(
        long generation,
        CapturedScreen screen,
        ActivityRecord record,
        Instant imageCapturedAt,
        dev.mikoto2000.rei.life.LifeContext.Lease lifeLease) {
      this.lifeLease = lifeLease;
      this.generation = generation;
      this.screen = screen;
      this.record = record;
      this.imageCapturedAt = imageCapturedAt;
      this.key = CandidateKey.of(record);
    }
  }

  /** 指定された値や利用先を使って、ActivityEvidencePipelineを初期化する。 */
  ActivityEvidencePipeline(
      ActivityProperties p,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      Clock clock,
      Executor foregroundExecutor,
      Executor backgroundExecutor,
      List<ActivityEvidenceSource> sources) {
    this.p = p;
    this.desktopPolicy = new DesktopAnalysisPolicy(p);
    this.observer = observer;
    this.extractor = extractor;
    this.store = store;
    this.screenshots = screenshots;
    this.clock = clock;
    this.foregroundExecutor = foregroundExecutor;
    aggregator = new ActivityEvidenceAggregator(p, sources);
  }

  /** 作業状況の把握に関する一時停止を処理する。 */
  synchronized void pause() {
    paused = true;
    invalidate();
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  synchronized void resume() {
    paused = false;
    invalidate();
  }

  /** 作業状況の把握で利用した資源や登録を解放し、利用を終了する。 */
  synchronized void close() {
    closed = true;
    pause();
  }

  /** 作業状況の把握に関するキャッシュの無効化を処理する。 */
  private synchronized void invalidate() {
    generation++;
    pending = null;
    desktopPending = null;
    desktopPolicy.reset();
    previous = null;
    lastSucceededAt = null;
    lastSucceededKey = null;
    lastSucceededResult = null;
    lastSucceededTiming = null;
    continuity = UUID.randomUUID().toString();
  }

  /** 作業状況の把握において、許可に該当するか判定する。 */
  private synchronized boolean allowed(long token) {
    return p.isEnabled()
        && !paused
        && !closed
        && generation == token
        && lifeAllowed(clock.instant());
  }

  /** 作業状況の把握に関する定期確認を処理する。 */
  void tick() {
    if (!lifeAllowed(clock.instant())) return;
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!observing.compareAndSet(false, true)) return;
    long started = System.nanoTime();
    try {
      long token;
      ActivityRecord prior;
      synchronized (this) {
        token = generation;
        prior = previous;
      }
      try {
        screenshots.cleanup(clock.instant().minus(Duration.ofDays(p.getScreenshotRetentionDays())));
      } catch (Exception e) {
        warn("retention", e);
      }
      if (!allowed(token)) return;
      var lifeLease = life == null ? null : life.begin();
      DesktopActivityObserver.Metadata metadata;
      try {
        metadata = observer.metadata();
      } catch (Exception e) {
        warn("metadata", e);
        metadata = null;
      }
      if (metadata == null)
        metadata = new DesktopActivityObserver.Metadata(observer.foreground(), List.of());
      var fg = metadata.foreground();
      if (new CapturePolicy(p).excluded(fg)) {
        invalidate();
        return;
      }
      var at = clock.instant();
      if (life != null && !life.valid(lifeLease)) return;
      var evidence = aggregator.collect(at, metadata, prior);
      if (life != null && !life.valid(lifeLease)) return;
      boolean idle = false;
      if (p.getDetection().isBackgroundFullScreenEnabled())
        try {
          var sample = observer.lightweight();
          boolean reliable = sample != null && sample.reliable();
          long age = reliable ? ((sample.uptimeMillis() - sample.lastInput()) & 0xffffffffL) : 0;
          reliable = reliable && age < 0x80000000L;
          idle = reliable && (sample.locked() || age >= 60000);
          evidence = evidence.withInput(new ActivityEvidence.InputReference(!idle, reliable));
        } catch (Exception ignored) {
        }

      ActivityClassification classification;
      try {
        classification =
            p.getDetection().isEvidenceEnabled()
                    && p.getDetection().getMode() != ActivityProperties.DetectionMode.VISION_FIRST
                ? classify.apply(evidence)
                : unknown(fg);
      } catch (Exception e) {
        warn("classification", e);
        classification = unknown(fg);
      }
      boolean fallback =
          p.isExtractionEnabled()
              && p.getDetection().isVisionEnabled()
              && (p.getDetection().getMode() == ActivityProperties.DetectionMode.VISION_FIRST
                  || p.getDetection().isFallbackEnabled())
              && !classification.usable(p.getDetection().getSkipVisionConfidence());
      ActivityRecord record;
      synchronized (this) {
        if (!allowed(token) || life != null && !life.valid(lifeLease)) return;
        boolean duplicateVision = fallback && duplicate(CandidateKey.of(evidence), at);
        boolean candidate = fallback;
        if (duplicateVision) fallback = false;
        var detection =
            new ActivityRecord.Detection(
                evidence,
                classification.sources(),
                false,
                "EVIDENCE_ONLY",
                fallback ? "PROVISIONAL" : "FINAL",
                classification.sourceConfidence(),
                classification.reason() + (duplicateVision ? "|VISION_DUPLICATE_SKIPPED" : ""),
                classification.fieldConfidence(),
                classification.secondaryConfidence(),
                null,
                VisionDiagnostics.initial());
        record =
            new ActivityRecord(
                UUID.randomUUID().toString(),
                at,
                p.observationDurationSeconds(
                    p.getObservation().isInputAwareEnabled()
                        && observation != null
                        && observation.lightweightAttempt()),
                List.of(),
                fg,
                classification.inference(),
                classification.confidence(),
                List.of(),
                0,
                false,
                continuity,
                detection);
        if (duplicateVision) record = historicalInference(record);
        store.append(record);
        previous = record;
        observations.increment();
        if (candidate) generated.increment();
        if (duplicateVision) duplicateSkipped.increment();
        if (observation != null) observation.saved();
        classificationCounts(record, 1);
        if (!fallback) {
          evidenceOnly.increment();
          skipped.increment();
        } else fallbacks.increment();
      }
      log.info(
          "Activity evidence saved: classification_ms={} confidence={} fallback={}",
          millis(started),
          record.confidence(),
          fallback);
      var axes = classification.fieldConfidence();
      log.debug(
          "Activity classification: category={} categoryConfidence={} applicationConfidence={} serviceConfidence={} projectConfidence={} contentConfidence={} usable={} complete={} visionSkipped={} matchedRule={}",
          record.inference().activities().getFirst().type(),
          axes.category(),
          axes.application(),
          axes.service(),
          axes.project(),
          axes.content(),
          classification.usable(p.getDetection().getSkipVisionConfidence()),
          classification.complete(),
          !fallback,
          classification.reason());
      boolean background;
      synchronized (this) {
        desktopPolicy.window(at, fg.windowId());
        background =
            p.isExtractionEnabled()
                && p.getDetection().isVisionEnabled()
                && metadata.complete()
                && desktopPolicy.due(
                    at,
                    idle,
                    Objects.toString(evidence.projectId(), "")
                        + ":"
                        + (evidence.workContext() == null ? 0 : evidence.workContext().revision())
                        + ":"
                        + (evidence.workContext() == null
                            ? ""
                            : Objects.toString(
                                evidence.workContext().git() == null
                                    ? null
                                    : evidence.workContext().git().branch(),
                                ""))
                        + ":"
                        + Objects.toString(metadata.visibleWindows(), "")
                        + ":"
                        + Objects.toString(fg.bounds(), ""));
      }
      boolean keep =
          new ScreenshotPersistencePolicy(p)
              .shouldSave(false, ScreenshotPersistencePolicy.Outcome.SUCCESS);
      if (!fallback && !background && !keep) {
        metrics();
        return;
      }
      if (!allowed(token) || !Objects.equals(fg, observer.foreground())) {
        metrics();
        return;
      }
      CapturedScreen screen;
      Instant imageCapturedAt;
      try {
        screen = observer.capture();
        imageCapturedAt = clock.instant();
      } catch (Exception e) {
        warn("screenshot", e);
        metrics();
        return;
      }
      if (!allowed(token) || !Objects.equals(fg, observer.foreground())) {
        metrics();
        return;
      }
      if (p.getDetection().isBackgroundFullScreenEnabled()) {
        var captures =
            screen.displays().stream()
                .map(
                    display -> {
                      var b = ActivityImages.physicalBounds(display.geometry());
                      return new ActivityRecord.Observation(
                          display.geometry().id(),
                          new ActivityRecord.Bounds(b.x, b.y, b.width, b.height),
                          imageCapturedAt);
                    })
                .toList();
        record =
            new ActivityRecord(
                record.id(),
                record.capturedAt(),
                record.durationEstimate(),
                captures,
                record.foreground(),
                record.inference(),
                record.confidence(),
                record.screenshotReferences(),
                record.changeAmount(),
                record.duplicate(),
                record.continuityId(),
                record.detection());
      }
      CapturedScreen desktop = null;
      if (p.getDetection().isBackgroundFullScreenEnabled()) {
        // Verify enumeration around capture; never mask from a stale or truncated window list.
        var after = observer.metadata();
        if (!metadata.equals(after) || !metadata.complete()) {
          metrics();
          return;
        }
        var sanitized = ActivityImages.privateDesktop(screen, metadata, p, false);
        if (sanitized == null) {
          metrics();
          return;
        }
        desktop = ActivityImages.selectDesktop(sanitized, p);
        if (desktop == null) background = false;
        screen = sanitized;
        // Keep the main crop separate from selected desktop monitors. Full-frame fallback fails
        // closed.
        var front = ActivityImages.foreground(screen, fg);
        if (fg.bounds() == null || front == screen) {
          metrics();
          return;
        }
        screen = front;
      }
      var work = new Work(token, screen, record, imageCapturedAt, lifeLease);
      work.desktop = desktop;
      if (keep)
        synchronized (this) {
          if (allowed(token)) {
            try {
              var refs = screenshots.save(record.id(), at, desktop == null ? screen : desktop);
              work.record =
                  copy(
                      work.record,
                      work.record.inference(),
                      work.record.confidence(),
                      work.record.detection(),
                      refs);
              store.replace(work.record);
            } catch (Exception e) {
              warn("evidence persistence", e);
            }
          }
        }
      // Publish both slots before scheduling, even for synchronous test executors.
      synchronized (this) {
        if (!allowed(token)) return;
        if (background) {
          desktopPending = work;
          desktopPolicy.started(at);
        }
        if (fallback) {
          if (pending != null) {
            skipped.increment();
            replaced.increment();
          }
          pending = work;
        }
      }
      pollForeground();
      metrics();
    } catch (Exception e) {
      warn("observation", e);
    } finally {
      observing.set(false);
    }
  }

  /** 作業状況の把握に関する状態確認前面を処理する。 */
  void pollForeground() {
    synchronized (this) {
      if (foregroundBusy || (pending == null && desktopPending == null)) return;
      if (pending != null && !startDue()) {
        deferred.increment();
        return;
      }
      foregroundBusy = true;
    }
    try {
      foregroundExecutor.execute(this::drain);
    } catch (RuntimeException e) {
      synchronized (this) {
        foregroundBusy = false;
        pending = null;
        desktopPending = null;
      }
      warn("foreground scheduling", e);
    }
  }

  /** 作業状況の把握に関する残っている対象の処理を処理する。 */
  private void drain() {
    while (true) {
      Work work;
      boolean background;
      synchronized (this) {
        if (pending != null) {
          if (!startDue()) {
            deferred.increment();
            foregroundBusy = false;
            return;
          }
          work = pending;
          pending = null;
          background = false;
          if (duplicate(work.key, clock.instant())) {
            duplicateSkipped.increment();
            if (allowed(work.generation) && lifeAllowed(work.record.capturedAt()))
              work.record = finishSkipped(work.record);
            continue;
          }
        } else {
          work = desktopPending;
          desktopPending = null;
          background = true;
          if (work == null) {
            foregroundBusy = false;
            return;
          }
          if ((!allowed(work.generation)
                  || (!lifeAllowed(work.record.capturedAt())
                      || life != null && !life.valid(work.lifeLease)))
              || !desktopPolicy.executionAllowed(clock.instant())
              || clock.instant().isBefore(work.record.capturedAt())
              || Duration.between(work.record.capturedAt(), clock.instant()).getSeconds()
                  > p.getBackgroundAnalysisIntervalSeconds()) continue;
        }
      }
      enrich(work, background);
    }
  }

  /** 要求された処理を利用できるよう、期限を開始する。 */
  private boolean startDue() {
    return !p.getVisionQueue().isEnabled()
        || foregroundStarted == null
        || !clock
            .instant()
            .isBefore(
                foregroundStarted.plusSeconds(p.getVisionQueue().getMinimumStartIntervalSeconds()));
  }

  /** 作業状況の把握において、重複の有無に該当するか判定する。 */
  private boolean duplicate(CandidateKey key, Instant at) {
    return p.getVisionQueue().isEnabled()
        && Objects.equals(key, lastSucceededKey)
        && lastSucceededAt != null
        && !at.isBefore(lastSucceededAt)
        && Duration.between(lastSucceededAt, at).getSeconds()
            < p.getVisionQueue().getMaxRefreshIntervalSeconds();
  }

  /** 終了時の状態を確定するため、対象外を完了させる。 */
  private ActivityRecord finishSkipped(ActivityRecord record) {
    var d = record.detection();
    var detection =
        new ActivityRecord.Detection(
            d.evidence(),
            d.classificationSources(),
            d.visionUsed(),
            d.classificationMode(),
            "FINAL",
            d.sourceConfidence(),
            d.reason() + "|VISION_DUPLICATE_SKIPPED",
            d.fieldConfidence(),
            d.secondaryConfidence(),
            d.diagnostics(),
            d.visionDiagnostics());
    var updated =
        copy(
            record,
            record.inference(),
            record.confidence(),
            detection,
            record.screenshotReferences());
    try {
      store.replace(updated);
      if (previous != null && previous.id().equals(updated.id())) previous = updated;
      return updated;
    } catch (Exception e) {
      warn("duplicate status", e);
      return record;
    }
  }

  /** 作業状況の把握で用いるhistorical推定を返す。 */
  private ActivityRecord historicalInference(ActivityRecord record) {
    // Results completed after this OS observation are never propagated to it.
    if (lastSucceededResult == null
        || lastSucceededTiming == null
        || lastSucceededTiming.completedAt() == null
        || lastSucceededTiming.completedAt().isAfter(record.capturedAt())
        || lastSucceededResult.confidence() < p.getPrimaryConfidenceThreshold()) return record;
    double confidence = Math.min(.7, lastSucceededResult.confidence());
    var inferred =
        ActivityEnrichment.merge(
            record, new ActivityExtractor.Result(lastSucceededResult.inference(), confidence));
    var d = inferred.detection();
    var sources = new LinkedHashSet<>(d.classificationSources());
    sources.add("HISTORY");
    var weights = new LinkedHashMap<>(d.sourceConfidence());
    weights.put("HISTORY", confidence);
    var vision = VisionDiagnostics.of(d).timing(false, lastSucceededTiming);
    var detection =
        new ActivityRecord.Detection(
            d.evidence(),
            List.copyOf(sources),
            false,
            "EVIDENCE_PLUS_HISTORY",
            "FINAL",
            weights,
            d.reason() + "|HISTORICAL_INFERENCE",
            d.fieldConfidence(),
            d.secondaryConfidence(),
            d.diagnostics(),
            vision);
    return copy(
        inferred,
        inferred.inference(),
        inferred.confidence(),
        detection,
        inferred.screenshotReferences());
  }

  /** 作業状況の把握に関するenrichを処理する。 */
  private void enrich(Work work, boolean background) {
    long started = System.nanoTime();
    Instant apiStarted = null, apiCompleted = null;
    boolean supplementSaved = false;
    ActivityRecord original;
    synchronized (this) {
      if ((!allowed(work.generation)
          || (!lifeAllowed(work.record.capturedAt())
              || life != null && !life.valid(work.lifeLease)))) return;
      original = work.record;
    }
    String source = background ? "VISION_BACKGROUND" : "VISION_FOREGROUND";
    String stage = "prepare";
    try (var ignored =
        org.slf4j.MDC.putCloseable("activityScope", background ? "background" : "foreground")) {
      var image =
          background
              ? work.desktop
              : !p.getDetection().isForegroundCrop()
                      || p.getDetection().isBackgroundFullScreenEnabled()
                  ? work.screen
                  : ActivityImages.foreground(work.screen, original.foreground());
      if (image == null) return;
      if (!background
          && !p.getDetection().isBackgroundFullScreenEnabled()
          && p.getDetection().isForegroundCrop()
          && image == work.screen) {
        skipped.increment();
        log.info(
            "Activity fallback skipped: reason=foreground_bounds_unavailable evidence_retained=true");
        return;
      }
      apiStarted = clock.instant();
      synchronized (this) {
        if ((!allowed(work.generation)
            || (!lifeAllowed(work.record.capturedAt())
                || life != null && !life.valid(work.lifeLease)))) return;
        if (background) {
          desktopPolicy.executed(apiStarted);
          backgroundCalls.increment();
        } else {
          foregroundCalls.increment();
          foregroundStarted = apiStarted;
        }
      }
      recordAttempt(work, background, apiStarted);
      stage = "extract";
      var result = extractor.extract(image, original.foreground());
      apiCompleted = clock.instant();
      stage = "merge";
      synchronized (this) {
        if ((!allowed(work.generation)
            || (!lifeAllowed(work.record.capturedAt())
                || life != null && !life.valid(work.lifeLease)))) return;
        var base = work.record;
        ActivityRecord merged;
        if (background)
          merged = ActivityBackgroundMerge.merge(base, result, p.getPrimaryConfidenceThreshold());
        else merged = ActivityEnrichment.merge(base, result);
        var d = merged.detection();
        var sources = new LinkedHashSet<>(d.classificationSources());
        sources.add(source);
        var weights = new LinkedHashMap<>(d.sourceConfidence());
        weights.put(source, result.confidence());
        var secondary = new ArrayList<>(d.secondaryConfidence());
        if (background)
          for (var candidate : merged.inference().activities())
            if (!base.inference().activities().contains(candidate))
              secondary.add(
                  new ActivityClassification.Secondary(
                      candidate,
                      ActivityFieldConfidence.from(candidate, result.confidence()).secondary()));
        boolean used =
            background
                ? VisionDiagnostics.of(merged.detection()).background().context() != null
                : !base.inference().equals(merged.inference())
                    || !ActivityEnrichment.fields(base).equals(ActivityEnrichment.fields(merged));
        var vision =
            VisionDiagnostics.of(d)
                .with(
                    background,
                    used
                        ? VisionDiagnostics.State.USED
                        : VisionDiagnostics.State.ATTEMPTED_SUCCEEDED_NOT_USED,
                    null)
                .timing(
                    background,
                    new VisionDiagnostics.Timing(
                        original.id(), work.imageCapturedAt, apiStarted, apiCompleted));
        var detection =
            new ActivityRecord.Detection(
                d.evidence(),
                List.copyOf(sources),
                true,
                "EVIDENCE_PLUS_VISION",
                "FINAL",
                weights,
                d.reason(),
                d.fieldConfidence(),
                secondary,
                d.diagnostics(),
                vision);
        var updated =
            copy(
                merged,
                merged.inference(),
                merged.confidence(),
                detection,
                merged.screenshotReferences());
        stage = "persist";
        store.replace(updated);
        work.record = updated;
        supplementSaved = true;
        if (!background) {
          completed.increment();
          lastSucceededKey = work.key;
          lastSucceededAt = work.imageCapturedAt;
          lastSucceededResult = result;
          lastSucceededTiming = vision.foreground().timing();
        }
        classificationCounts(base, -1);
        classificationCounts(work.record, 1);
        if (previous != null && previous.id().equals(work.record.id())) previous = work.record;
        success.increment();
      }
      log.info(
          "Activity evidence enriched: scope={} observation_age_ms={}",
          background ? "background" : "foreground",
          Duration.between(original.capturedAt(), clock.instant()).toMillis());
    } catch (Exception e) {
      if (apiStarted != null && apiCompleted == null) apiCompleted = clock.instant();
      if (!background) failed.increment();
      failure.increment();
      var kind = ActivityVisionFailure.classify(e);
      switch (kind) {
        case OUTPUT_LIMIT -> outputLimits.increment();
        case TIMEOUT -> timeout.increment();
        case VALIDATION -> validationFailures.increment();
        default -> {}
      }
      var failureDetails = ActivityVisionFailure.details(e);
      log.warn(
          "Activity Vision failure: scope={} reason={} evidence_retained=true observation_id={} stage={} error_type={} cause_types={} http_status={} cause_chain_truncated={} provider_code={} provider_param={} provider_type={} provider_hint={}",
          background ? "background" : "foreground",
          kind,
          original.id(),
          stage,
          failureDetails.errorType(),
          failureDetails.causes(),
          failureDetails.httpStatus(),
          failureDetails.truncated(),
          failureDetails.provider().code(),
          failureDetails.provider().param(),
          failureDetails.provider().type(),
          failureDetails.provider().hint());
      synchronized (this) {
        if (allowed(work.generation) && lifeAllowed(work.record.capturedAt()))
          try {
            var r = work.record;
            var d = r.detection();
            var sources = new LinkedHashSet<>(d.classificationSources());
            sources.add(source);
            var status = d.status().equals("FINAL") ? "FINAL" : "VISION_FAILED";
            String reason =
                d.reason().split("\\|", 2)[0]
                    + "|"
                    + switch (kind) {
                      case OUTPUT_LIMIT -> "VISION_OUTPUT_LIMIT";
                      case TIMEOUT -> "VISION_TIMEOUT";
                      case VALIDATION -> "VISION_VALIDATION_FAILED";
                      default -> "VISION_FAILED";
                    };
            var vision =
                VisionDiagnostics.of(d)
                    .with(background, VisionDiagnostics.State.ATTEMPTED_FAILED, kind)
                    .timing(
                        background,
                        new VisionDiagnostics.Timing(
                            original.id(), work.imageCapturedAt, apiStarted, apiCompleted));
            var detection =
                new ActivityRecord.Detection(
                    d.evidence(),
                    List.copyOf(sources),
                    true,
                    d.classificationMode(),
                    status,
                    d.sourceConfidence(),
                    reason,
                    d.fieldConfidence(),
                    d.secondaryConfidence(),
                    d.diagnostics(),
                    vision);
            var refs = r.screenshotReferences();
            if (new ScreenshotPersistencePolicy(p)
                .shouldSave(false, ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE))
              try {
                refs = screenshots.save(r.id(), r.capturedAt(), work.screen);
              } catch (Exception imageError) {
                warn("failure evidence", imageError);
              }
            work.record = copy(r, r.inference(), r.confidence(), detection, refs);
            store.replace(work.record);
            supplementSaved = true;
          } catch (Exception storageError) {
            warn("failure status", storageError);
          }
      }
    } finally {
      if (!background && apiStarted != null) {
        long wait = elapsed(work.imageCapturedAt, apiStarted),
            execution = elapsed(apiStarted, apiCompleted == null ? clock.instant() : apiCompleted);
        queueWaitMillis.add(wait);
        executionMillis.add(execution);
        long latency = supplementSaved ? elapsed(original.capturedAt(), clock.instant()) : 0;
        if (supplementSaved) endToEndMillis.add(latency);
        log.info(
            "Activity vision observation timing: observationId={} image_at={} started_at={} completed_at={} queue_wait_ms={} execution_ms={} supplement_latency_ms={} saved={}",
            original.id(),
            work.imageCapturedAt,
            apiStarted,
            apiCompleted,
            wait,
            execution,
            latency,
            supplementSaved);
      }
      log.info(
          "Activity enrichment timing: scope={} vision_ms={}",
          background ? "background" : "foreground",
          millis(started));
      metrics();
    }
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private ActivityClassification unknown(ForegroundWindow fg) {
    return new ActivityClassification(
        new ActivityRecord.Inference(
            "主活動を判定できないOS観測",
            List.of(
                new ActivityRecord.Activity(
                    "foreground", "unknown", fg.processName(), "", "", ""))),
        0,
        List.of("FOREGROUND_WINDOW"),
        Map.of("FOREGROUND_WINDOW", 1.0),
        "insufficient_evidence",
        new ActivityFieldConfidence(0, 1, 0, 0, 0),
        List.of());
  }

  /** 後から経緯や使用量を確認できるよう、試行を記録する。 */
  private synchronized void recordAttempt(Work work, boolean background, Instant startedAt) {
    if ((!allowed(work.generation)
        || (!lifeAllowed(work.record.capturedAt()) || life != null && !life.valid(work.lifeLease))))
      return;
    var r = work.record;
    var d = r.detection();
    var vision =
        VisionDiagnostics.of(d)
            .with(background, VisionDiagnostics.State.ATTEMPTED, null)
            .timing(
                background,
                new VisionDiagnostics.Timing(r.id(), work.imageCapturedAt, startedAt, null));
    var detection =
        new ActivityRecord.Detection(
            d.evidence(),
            d.classificationSources(),
            d.visionUsed(),
            d.classificationMode(),
            d.status(),
            d.sourceConfidence(),
            d.reason(),
            d.fieldConfidence(),
            d.secondaryConfidence(),
            d.diagnostics(),
            vision);
    work.record = copy(r, r.inference(), r.confidence(), detection, r.screenshotReferences());
    try {
      store.replace(work.record);
    } catch (Exception e) {
      warn("attempt diagnostics", e);
    }
  }

  /** 作業状況の把握で用いる複製を返す。 */
  private static ActivityRecord copy(
      ActivityRecord r,
      ActivityRecord.Inference inference,
      double confidence,
      ActivityRecord.Detection detection,
      List<String> refs) {
    return new ActivityRecord(
        r.id(),
        r.capturedAt(),
        r.durationEstimate(),
        r.observations(),
        r.foreground(),
        inference,
        confidence,
        refs,
        r.changeAmount(),
        r.duplicate(),
        r.continuityId(),
        detection);
  }

  /** 作業状況の把握に関する計測値を処理する。 */
  private void metrics() {
    var q = queueMetrics();
    log.info(
        "Activity vision queue metrics: generated={} started={} completed={} failed={} replaced={} duplicate_skipped={} deferred={} api_calls={} queue_wait_total_ms={} execution_total_ms={} supplement_latency_total_ms={} pending={} worker_busy={}",
        q.generated(),
        q.started(),
        q.completed(),
        q.failed(),
        q.replaced(),
        q.duplicateSkipped(),
        q.deferred(),
        q.apiCalls(),
        q.queueWaitMillis(),
        q.executionMillis(),
        q.endToEndMillis(),
        q.pending(),
        q.running());
    long count = observations.sum(), calls = foregroundCalls.sum() + backgroundCalls.sum();
    log.info(
        "Activity detection metrics: observations={} evidence_only={} vision_fallback={} vision_skipped={} foreground_vision={} background_vision={} vision_success={} vision_failure={} vision_timeout={} vision_call_rate={}",
        count,
        evidenceOnly.sum(),
        fallbacks.sum(),
        skipped.sum(),
        foregroundCalls.sum(),
        backgroundCalls.sum(),
        success.sum(),
        failure.sum(),
        timeout.sum(),
        count == 0 ? 0 : (double) calls / count);
    log.info(
        "Activity classification metrics: observations={} unknown={} partial={} vision_output_limit={} vision_validation={} evidence_only_rate={} vision_success_rate={} unknown_rate={} partial_rate={} usable={} usable_rate={}",
        count,
        unknownCount.sum(),
        partialCount.sum(),
        outputLimits.sum(),
        validationFailures.sum(),
        rate(evidenceOnly.sum(), count),
        rate(success.sum(), success.sum() + failure.sum()),
        rate(unknownCount.sum(), count),
        rate(partialCount.sum(), count),
        usableCount.sum(),
        rate(usableCount.sum(), count));
  }

  /** 作業状況の把握に関する分類件数を処理する。 */
  private void classificationCounts(ActivityRecord record, int delta) {
    if (record.inference().activities().isEmpty()
        || ActivityVocabulary.category(record.inference().activities().getFirst().type())
            .equals("unknown")) unknownCount.add(delta);
    if (ActivityEnrichment.fields(record).partial()) partialCount.add(delta);
    if (ActivityEnrichment.fields(record).usable(p.getDetection().getSkipVisionConfidence()))
      usableCount.add(delta);
  }

  /** 作業状況の把握で用いる頻度を返す。 */
  private static double rate(long count, long total) {
    return total == 0 ? 0 : (double) count / total;
  }

  /** 作業状況の把握で用いる経過時間を返す。 */
  private static long elapsed(Instant start, Instant end) {
    return Math.max(0, Duration.between(start, end).toMillis());
  }

  /** 作業状況の把握で用いるミリ秒数を返す。 */
  private static long millis(long start) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** 作業状況の把握に関するwarnを処理する。 */
  private static void warn(String stage, Exception e) {
    log.warn("Activity evidence {} failed ({})", stage, e.getClass().getSimpleName());
  }
}
