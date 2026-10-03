package dev.mikoto2000.rei.application.read;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
public record ProfileQueryService(ProfileEventLogStore store) {
  public ProfileEventLogStore.ProfileSummary summary() { return store.summarize(); }
}
