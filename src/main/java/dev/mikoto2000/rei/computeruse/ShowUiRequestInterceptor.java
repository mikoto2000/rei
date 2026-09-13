package dev.mikoto2000.rei.computeruse;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Spring AI UserMessage puts all text before media. Restore ShowUI's ordered content on the wire. */
public final class ShowUiRequestInterceptor implements ClientHttpRequestInterceptor {
  public static final String INSTRUCTION = "Based on the screenshot of the page, I give a text description and you give its corresponding location. The coordinate represents a clickable location [x, y] for an element, which is a relative coordinate on the screenshot, scaled from 0 to 1.";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    if (!request.getURI().getPath().endsWith("/chat/completions")) return execution.execute(request, body);
    var root = JSON.readTree(body);
    var messages = root.path("messages");
    // Only the isolated ShowUI grounding request has this exact single-message shape.
    if (messages.isArray() && messages.size() == 1 && "user".equals(messages.get(0).path("role").asText())) {
      var content = messages.get(0).path("content");
      if (content.isArray() && content.size() == 2
          && "text".equals(content.get(0).path("type").asText())
          && "image_url".equals(content.get(1).path("type").asText())) {
        String text = content.get(0).path("text").asText();
        if (text.startsWith(INSTRUCTION + "\n")) {
          var ordered = JSON.createArrayNode();
          ordered.addObject().put("type", "text").put("text", INSTRUCTION);
          ordered.add(content.get(1));
          ordered.addObject().put("type", "text").put("text", text.substring(INSTRUCTION.length() + 1));
          ((ObjectNode) messages.get(0)).set("content", ordered);
          body = JSON.writeValueAsBytes(root);
          request.getHeaders().setContentLength(body.length);
        }
      }
    }
    return execution.execute(request, body);
  }
}
