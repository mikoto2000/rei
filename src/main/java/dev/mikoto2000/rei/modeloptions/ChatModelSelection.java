package dev.mikoto2000.rei.modeloptions;

/** Model controls for the process-wide chat selection; no provider or credential dependencies. */
public interface ChatModelSelection {
  record Selection(String endpoint, String model) {}

  record Models(Selection selection, java.util.List<String> models) {
    public Models {
      java.util.Objects.requireNonNull(selection, "selection");
      models = java.util.List.copyOf(models);
    }
  }

  Models models();

  Selection selection();

  void selectModel(String model);

  /** Atomically reject a choice made for a different endpoint. */
  default void selectModel(String expectedEndpoint, String model) {
    throw new IllegalStateException("Endpoint-bound model selection is unavailable");
  }
}
