package dev.mikoto2000.rei.modeloptions;

/** Model controls for the process-wide chat selection; no provider or credential dependencies. */
public interface ChatModelSelection {
  record Selection(String endpoint, String model) {}

  Selection selection();

  void selectModel(String model);
}
