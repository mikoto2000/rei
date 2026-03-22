package dev.mikoto2000.rei.command;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ListModelResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

@Component
@Command(name = "models", description = "モデルの一覧を表示します")
@RequiredArgsConstructor
public class ModelsCommand implements Runnable {

  private final OllamaApi ollamaApi;

  @Override
  public void run() {

    ListModelResponse modelsRes = ollamaApi.listModels();

    modelsRes.models().stream()
      .forEach(e -> IO.println(e.name()));
  }
}
