package dev.mikoto2000.rei;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@SpringBootApplication
public class ReiApplication {

  private final ChatModel chatModel;

  public  static void main(String[] args) {
    var context = SpringApplication.run(ReiApplication.class, args);
    var app = context.getBean(ReiApplication.class);
    app.run(args);
    context.close();
  }

  private void run(String[] args) {
    ChatResponse chatResponse = chatModel.call(new Prompt("こんにちは！"));

    String thinking = chatResponse.getResult().getMetadata().get("thinking");
    IO.println(thinking);
    String answer = chatResponse.getResult().getOutput().getText();
    IO.println(answer);
  }

}
