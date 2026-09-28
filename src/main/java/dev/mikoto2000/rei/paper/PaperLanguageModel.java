package dev.mikoto2000.rei.paper;

public interface PaperLanguageModel {
  String model();

  String generate(String system, String input, PaperOperation op);
}
