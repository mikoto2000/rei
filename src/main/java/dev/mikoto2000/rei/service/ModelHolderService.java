package dev.mikoto2000.rei.service;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelHolderService {
  private AtomicReference<String> currentModel;

  public ModelHolderService(
      @Value("${spring.ai.ollama.chat.options.model}")
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
