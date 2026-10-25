package dev.mikoto2000.rei.secretary;

/** Human-facing requests use the existing approval owner, never a model-generated grant. */
public interface SecretaryPermissions {
  String requestSecretaryPermission(String project, String task, String kind);

  default String requestSecretaryAction(String project, String action, String canonical) {
    throw new IllegalStateException("Secretary action approval unavailable");
  }

  default String secretaryActionStatus(String project, String id, String action, String canonical) {
    return "UNAVAILABLE";
  }

  default boolean consumeSecretaryAction(
      String project, String id, String action, String canonical) {
    return false;
  }

  default String secretaryPreparationStatus(String project, String id, String task, String rule) {
    return "UNAVAILABLE";
  }

  default boolean consumeSecretaryPreparation(String project, String id, String task, String rule) {
    return false;
  }
}
