package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;

/** Manual frozen-image inference only: never constructs Robot or dispatches input. */
public final class ComputerVisionReplay {
  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("baseUrl model savedStepDirectory outputDirectory goal");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var source = Path.of(args[2]);
    var displays = new ArrayList<DisplayCapture>();
    var metadata = json.readTree(source.resolve("displays.json").toFile());
    Rectangle virtual = null;
    for (var item : metadata) { var b = item.get("bounds"); var bounds = new Rectangle(b.get("x").asInt(),b.get("y").asInt(),b.get("width").asInt(),b.get("height").asInt()); virtual = virtual == null ? bounds : virtual.union(bounds); }
    for (var item : metadata) {
      var b = item.get("bounds");
      var geometry = new ScreenGeometry(item.get("displayId").asText(),new Rectangle(b.get("x").asInt(),b.get("y").asInt(),b.get("width").asInt(),b.get("height").asInt()),
          virtual,item.get("primary").asBoolean(),item.get("scaleX").asDouble(),item.get("scaleY").asDouble());
      displays.add(new DisplayCapture(geometry,ImageIO.read(source.resolve(item.get("image").asText()).toFile())));
    }
    String key = System.getenv("REI_OPENAI_API_KEY");
    var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
        .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(15)).build());
    factory.setReadTimeout(java.time.Duration.ofMinutes(4));
    var api = OpenAiApi.builder().baseUrl(args[0]).apiKey(key == null || key.isBlank() ? "dummy-key" : key)
        .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(factory)).build();
    var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model(args[1]).build()).build();
    var diagnostics = new ComputerDiagnostics(Path.of(args[3]));
    var run = diagnostics.begin(); var screen = new CapturedScreen(displays);
    diagnostics.observed(run,1,screen);
    System.out.println("Replay diagnostics: " + run);
    var vision = new SpringAiComputerVisionModel(model,()->OpenAiChatOptions.builder().model(args[1]).maxTokens(4096),()->false,0);
    var action = vision.decide(new ComputerObservation(args[4],screen,List.of(),1,20,run));
    diagnostics.action(run,1,screen,action,"decided");
    System.out.println(json.writeValueAsString(action));
    System.out.println("No input was dispatched.");
  }
}
