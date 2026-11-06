package dev.mikoto2000.rei.application.read;

import dev.mikoto2000.rei.event.ProfileEventLogStore;
import java.util.function.Supplier;

/** 保存済みの利用者情報を読み取る。 */
public final class ProfileQueryService {
  /** 利用者情報の要約を取得する操作。 */
  private final Supplier<ProfileEventLogStore.ProfileSummary> summarize;

  /** 要約に必要な操作を受け取る。 */
  public ProfileQueryService(ProfileEventLogStore store) {
    summarize = () -> store.summarize();
  }

  /** 情報の照会で用いる要約を返す。 */
  public ProfileEventLogStore.ProfileSummary summary() {
    return summarize.get();
  }
}
