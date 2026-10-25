package dev.mikoto2000.rei.secretary;

/** Human-facing requests use the existing approval owner, never a model-generated grant. */
public interface SecretaryPermissions {
  String requestSecretaryPermission(String project, String task, String kind);
}
