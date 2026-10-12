package dev.mikoto2000.rei.review;
import java.nio.file.*;import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import dev.mikoto2000.rei.storage.StorageDatabase;
/** Explicit opt-in OpenAI-compatible endpoint. No network, credentials or model choice during normal CI. */
@Tag("live") @EnabledIfEnvironmentVariable(named="REI_REVIEW_EVAL_LIVE",matches="true")
class ReviewQualityLiveTest {
 @TempDir Path dir;
 @Test void measureLiveQualityUsingTheSameVerifiedDeliveryProtocol()throws Exception {
  String url=Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_URL"),"REI_REVIEW_EVAL_URL required");
  String model=Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_MODEL"),"REI_REVIEW_EVAL_MODEL required");
  String key=Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_API_KEY"),"REI_REVIEW_EVAL_API_KEY required");
  var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  ReviewQualityEvaluationTest.evaluate(dir,prompt->{try{
   var messages=prompt.getInstructions().stream().map(m->Map.of("role",m instanceof org.springframework.ai.chat.messages.SystemMessage?"system":"user",
    "content",m instanceof org.springframework.ai.chat.messages.ToolResponseMessage t?t.getResponses().getFirst().responseData():m.getText())).toList();
   String body=StorageDatabase.JSON.writeValueAsString(Map.of("model",model,"messages",messages,"max_tokens",4096,"temperature",0,"response_format",Map.of("type","json_object")));
   var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("Evaluation HTTP status "+response.statusCode());
   return StorageDatabase.JSON.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
  }catch(Exception error){throw new IllegalStateException("Live evaluation failed",error);}});
 }
}
