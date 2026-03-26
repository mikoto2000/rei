package dev.mikoto2000.rei.core.service;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelHolderService {
  private final AtomicReference<String> currentModel;

  public ModelHolderService(
      @Value("${spring.ai.openai.chat.options.model}")
      String defaultModel
      ) {
    this.currentModel = new AtomicReference<>(defaultModel);
  }

  public String get() {
    return currentModel.get();
  }

  public void set(String model) {
    this.currentModel.set(model);
  }
}
