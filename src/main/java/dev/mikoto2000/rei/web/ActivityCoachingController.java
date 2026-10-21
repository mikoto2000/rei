package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.activity.PeriodCoaching;
import dev.mikoto2000.rei.activity.PeriodCoachingService;
import dev.mikoto2000.rei.activity.PeriodCoachingStore;
import java.util.ConcurrentModificationException;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 作業状況助言をWeb APIの操作として受け付ける。
 *
 * <p>Journal-wide explicit preferences. Reading/changing settings never assesses or reserves
 * advice.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActivityCoachingController {
  /**
   * Web APIによる操作で受け渡す状態を表す。
   *
   * @param schemaVersion データ形式形式のバージョン
   * @param scope 対象範囲
   * @param revision 改訂番号
   * @param settings 設定
   */
  public record State(
      int schemaVersion, String scope, long revision, PeriodCoaching.Settings settings) {}

  /**
   * Web APIによる操作で受け渡す設定要求を表す。
   *
   * @param expectedRevision 期待する内容改訂番号
   * @param settings 設定
   */
  public record ConfigureRequest(Long expectedRevision, PeriodCoaching.Settings settings) {}

  /**
   * Web APIによる操作で受け渡す有効化要求を表す。
   *
   * @param expectedRevision 期待する内容改訂番号
   * @param enabled 有効状態
   */
  public record EnableRequest(Long expectedRevision, Boolean enabled) {}

  /** サービス一覧。 */
  private final ObjectProvider<PeriodCoachingService> services;

  /** 指定された値や利用先を使って、ActivityCoachingControllerを初期化する。 */
  public ActivityCoachingController(ObjectProvider<PeriodCoachingService> services) {
    this.services = services;
  }

  /** Web APIによる操作で用いるサービスを返す。 */
  private PeriodCoachingService service() {
    var service = services.getIfAvailable();
    if (service == null)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Activity coaching is disabled");
    return service;
  }

  /** Web APIによる操作で用いる状態を返す。 */
  private State state(PeriodCoachingStore.Snapshot snapshot) {
    return new State(1, "LOCAL_DEVICE_COACHING_SETTINGS", snapshot.revision(), snapshot.settings());
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @GetMapping("/api/v1/activity/coaching")
  public State status() {
    return state(service().status());
  }

  /** Web APIによる操作で用いる設定を返す。 */
  @PostMapping("/api/v1/activity/coaching/settings")
  public State configure(@RequestBody ConfigureRequest request) {
    if (request == null || request.settings() == null || request.settings().enabled())
      throw new IllegalArgumentException("Save disabled coaching settings before explicit enable");
    long revision = revision(request.expectedRevision());
    return update(() -> service().configureExpected(request.settings(), revision));
  }

  /** Web APIによる操作で用いる有効化を返す。 */
  @PostMapping("/api/v1/activity/coaching/enabled")
  public State enable(@RequestBody EnableRequest request) {
    if (request == null || request.enabled() == null)
      throw new IllegalArgumentException("Require explicit enabled flag");
    long revision = revision(request.expectedRevision());
    return update(() -> service().setEnabledExpected(request.enabled(), revision));
  }

  /** Web APIによる操作で用いる改訂番号を返す。 */
  private long revision(Long revision) {
    if (revision == null || revision < 0 || revision == Long.MAX_VALUE)
      throw new IllegalArgumentException("Require viewed coaching revision");
    return revision;
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private State update(Supplier<PeriodCoachingStore.Snapshot> update) {
    try {
      return state(update.get());
    } catch (ConcurrentModificationException conflict) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Coaching settings changed; reload before updating");
    } catch (UnsupportedOperationException unsupported) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Revision-checked coaching updates unavailable");
    }
  }
}
