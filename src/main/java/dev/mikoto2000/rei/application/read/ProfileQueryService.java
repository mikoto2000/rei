package dev.mikoto2000.rei.application.read;

import dev.mikoto2000.rei.event.ProfileEventLogStore;

/**
 * 情報の照会で受け渡す利用者情報検索条件サービスを表す。
 *
 * @param store 保存先
 */
public record ProfileQueryService(ProfileEventLogStore store) {
  /** 情報の照会で用いる要約を返す。 */
  public ProfileEventLogStore.ProfileSummary summary() {
    return store.summarize();
  }
}
