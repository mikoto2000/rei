package dev.mikoto2000.rei.doctor;

import java.time.Instant;

/** Observed scope is explicit: an OK configuration is never a live service health claim. */
public record DoctorResult(String id, Status status, Instant observedAt, String method,
    String evidence, String causeCandidates, String nextAction) {
  public enum Status { OK, WARNING, ERROR, NOT_CONFIGURED, NOT_APPLICABLE, UNVERIFIED, SKIPPED }
}
