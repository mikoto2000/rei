package dev.mikoto2000.rei.secretary;

/** Idempotent immutable drafts use the existing artifact owner and capacity/expiry controls. */
public interface SecretaryDrafts {
  record Draft(String id, String sha256) {}

  Draft save(String project, String session, String job, String text);

  Draft find(String project, String session, String job);

  boolean verify(String project, String session, Draft draft);
}
