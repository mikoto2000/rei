package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.StatefulOperationService;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** StatefulをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class StatefulController {
  /** 操作一覧。 */
  private final FeedWrites feedWrites;

  private final ReminderAccess reminderAccess;
  private final InterestAccess interestAccess;
  private final MemoryAccess memoryAccess;
  private final IntSupplier reloadSkills;

  /** 指定された値や利用先を使って、StatefulControllerを初期化する。 */
  public StatefulController(StatefulOperationService operations) {
    this.feedWrites =
        new FeedWrites(
            request ->
                FeedResponse.from(operations.createFeed(request.url(), request.displayName())),
            (id, request) ->
                FeedResponse.from(
                    operations.updateFeed(id, request.displayName(), request.enabled())),
            id -> operations.deleteFeed(id));
    this.reminderAccess =
        new ReminderAccess(
            () -> operations.remindersList().stream().map(ReminderResponse::from).toList(),
            id -> ReminderResponse.from(operations.reminder(id)),
            request ->
                ReminderResponse.from(
                    operations.createReminder(
                        request.message(),
                        request.at(),
                        request.target(),
                        request.minutesBefore())),
            id -> operations.deleteReminder(id));
    this.interestAccess =
        new InterestAccess(
            hours -> operations.interestsList(hours).stream().map(InterestResponse::from).toList(),
            request ->
                InterestResponse.from(
                    operations.createInterest(
                        request.topic(),
                        request.reason(),
                        request.searchQuery(),
                        request.summary(),
                        request.sourceUrls())));
    this.memoryAccess =
        new MemoryAccess(
            () -> operations.memoriesList().stream().map(MemoryResponse::from).toList(),
            id -> MemoryResponse.from(operations.memory(id)),
            request ->
                MemoryResponse.from(
                    operations.createMemory(
                        request.content(), request.type(), request.scope(), request.confidence())),
            id -> operations.deleteMemory(id));
    this.reloadSkills = () -> operations.reloadSkills();
  }

  /**
   * Web APIによる操作で受け渡す作成フィード要求を表す。
   *
   * @param url 参照先のURL
   * @param displayName 表示名前
   */
  public record CreateFeedRequest(String url, String displayName) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す更新フィード要求を表す。
   *
   * @param displayName 表示名前
   * @param enabled 有効状態
   */
  public record UpdateFeedRequest(String displayName, Boolean enabled)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す作成リマインダー要求を表す。
   *
   * @param message メッセージ
   * @param at 日時
   * @param target 対象
   * @param minutesBefore 分数変更前
   */
  public record CreateReminderRequest(
      String message, String at, String target, Integer minutesBefore)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す作成関心要求を表す。
   *
   * @param topic 話題
   * @param reason 理由
   * @param searchQuery 検索検索条件
   * @param summary 要約
   * @param sourceUrls 取得元URL一覧
   */
  public record CreateInterestRequest(
      String topic, String reason, String searchQuery, String summary, List<String> sourceUrls)
      implements StrictApiRequest {
    public CreateInterestRequest {
      sourceUrls =
          sourceUrls == null ? null : Collections.unmodifiableList(new ArrayList<>(sourceUrls));
    }
  }

  /**
   * Web APIによる操作で受け渡す作成記憶要求を表す。
   *
   * @param content 内容
   * @param type 種別
   * @param scope 対象範囲
   * @param confidence 確信度
   */
  public record CreateMemoryRequest(String content, String type, String scope, Double confidence)
      implements StrictApiRequest {}

  /** Web APIによる操作で受け渡す再読み込みスキル一覧要求を表す。 */
  public record ReloadSkillsRequest() implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すスキル再読み込み応答を表す。
   *
   * @param count 件数
   */
  public record SkillReloadResponse(int count) {}

  /** 新たな対象を利用できるよう、フィードを作成する。 */
  @PostMapping("/feed")
  public ResponseEntity<FeedResponse> createFeed(@RequestBody CreateFeedRequest r) {
    var response = feedWrites.create().apply(r);
    return ResponseEntity.created(URI.create("/api/v1/feed/" + response.id())).body(response);
  }

  /** 最新の内容を後続の処理に反映するため、フィードを更新する。 */
  @PatchMapping("/feed/{id}")
  public FeedResponse updateFeed(@PathVariable long id, @RequestBody UpdateFeedRequest r) {
    return feedWrites.update().apply(id, r);
  }

  /** 対象を管理から外すため、フィードを削除する。 */
  @DeleteMapping("/feed/{id}")
  public ResponseEntity<Void> deleteFeed(@PathVariable long id) {
    feedWrites.delete().accept(id);
    return ResponseEntity.noContent().build();
  }

  /** Web APIによる操作で用いるリマインダー一覧を返す。 */
  @GetMapping("/reminders")
  public List<ReminderResponse> reminders() {
    return reminderAccess.list().get();
  }

  /** Web APIによる操作で用いるリマインダーを返す。 */
  @GetMapping("/reminders/{id}")
  public ReminderResponse reminder(@PathVariable long id) {
    return reminderAccess.find().apply(id);
  }

  /** 新たな対象を利用できるよう、リマインダーを作成する。 */
  @PostMapping("/reminders")
  public ResponseEntity<ReminderResponse> createReminder(@RequestBody CreateReminderRequest r) {
    var response = reminderAccess.create().apply(r);
    return ResponseEntity.created(URI.create("/api/v1/reminders/" + response.id())).body(response);
  }

  /** 対象を管理から外すため、リマインダーを削除する。 */
  @DeleteMapping("/reminders/{id}")
  public ResponseEntity<Void> deleteReminder(@PathVariable long id) {
    reminderAccess.delete().accept(id);
    return ResponseEntity.noContent().build();
  }

  /** Web APIによる操作で用いる関心一覧を返す。 */
  @GetMapping("/interests")
  public List<InterestResponse> interests(@RequestParam(defaultValue = "24") int hours) {
    return interestAccess.list().apply(hours);
  }

  /** 新たな対象を利用できるよう、関心を作成する。 */
  @PostMapping("/interests")
  public ResponseEntity<InterestResponse> createInterest(@RequestBody CreateInterestRequest r) {
    return ResponseEntity.status(201).body(interestAccess.create().apply(r));
  }

  /** Web APIによる操作で用いる記憶一覧を返す。 */
  @GetMapping("/memories")
  public List<MemoryResponse> memories() {
    return memoryAccess.list().get();
  }

  /** Web APIによる操作で用いる記憶を返す。 */
  @GetMapping("/memories/{id}")
  public MemoryResponse memory(@PathVariable String id) {
    return memoryAccess.find().apply(id);
  }

  /** 新たな対象を利用できるよう、記憶を作成する。 */
  @PostMapping("/memories")
  public ResponseEntity<MemoryResponse> createMemory(@RequestBody CreateMemoryRequest r) {
    var response = memoryAccess.create().apply(r);
    return ResponseEntity.created(URI.create("/api/v1/memories/" + response.id())).body(response);
  }

  /** 対象を管理から外すため、記憶を削除する。 */
  @DeleteMapping("/memories/{id}")
  public ResponseEntity<Void> deleteMemory(@PathVariable String id) {
    memoryAccess.delete().accept(id);
    return ResponseEntity.noContent().build();
  }

  /** 変更された設定や記録を反映するため、Stateful受付窓口を読み直す。 */
  @PostMapping("/skills/reload")
  public SkillReloadResponse reload(@RequestBody(required = false) ReloadSkillsRequest r) {
    return new SkillReloadResponse(reloadSkills.getAsInt());
  }

  private record FeedWrites(
      Function<CreateFeedRequest, FeedResponse> create,
      BiFunction<Long, UpdateFeedRequest, FeedResponse> update,
      LongConsumer delete) {}

  private record ReminderAccess(
      Supplier<List<ReminderResponse>> list,
      LongFunction<ReminderResponse> find,
      Function<CreateReminderRequest, ReminderResponse> create,
      LongConsumer delete) {}

  private record InterestAccess(
      IntFunction<List<InterestResponse>> list,
      Function<CreateInterestRequest, InterestResponse> create) {}

  private record MemoryAccess(
      Supplier<List<MemoryResponse>> list,
      Function<String, MemoryResponse> find,
      Function<CreateMemoryRequest, MemoryResponse> create,
      Consumer<String> delete) {}
}
