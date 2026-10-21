package dev.mikoto2000.rei.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 画面操作の支援に必要な表示表示要求Interceptorを扱う。
 *
 * <p>Spring AI UserMessage puts all text before media. Restore ShowUI's ordered content on the
 * wire.
 */
public final class ShowUiRequestInterceptor implements ClientHttpRequestInterceptor {
  /** 指示。 */
  public static final String INSTRUCTION =
      "Based on the screenshot of the page, I give a text description and you give its corresponding location. The coordinate represents a clickable location [x, y] for an element, which is a relative coordinate on the screenshot, scaled from 0 to 1.";

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 画面操作の支援で用いるinterceptを返す。 */
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    if (!request.getURI().getPath().endsWith("/chat/completions"))
      return execution.execute(request, body);
    var rewritten = rewriteBody(body);
    if (rewritten != body) request.getHeaders().setContentLength(rewritten.length);
    return execution.execute(request, rewritten);
  }

  /**
   * 画面操作の支援で用いるrewrite本文を返す。
   *
   * <p>Shared JSON transformation for Spring HTTP and the OpenAI SDK transport.
   */
  public static byte[] rewriteBody(byte[] body) throws IOException {
    var root = JSON.readTree(body);
    var messages = root.path("messages");
    // Only the isolated ShowUI grounding request has this exact single-message shape.
    if (messages.isArray()
        && messages.size() == 1
        && "user".equals(messages.get(0).path("role").asText())) {
      var content = messages.get(0).path("content");
      if (content.isArray()
          && content.size() == 2
          && "text".equals(content.get(0).path("type").asText())
          && "image_url".equals(content.get(1).path("type").asText())) {
        String text = content.get(0).path("text").asText();
        if (text.startsWith(INSTRUCTION + "\n")) {
          var ordered = JSON.createArrayNode();
          ordered.addObject().put("type", "text").put("text", INSTRUCTION);
          ordered.add(content.get(1));
          ordered
              .addObject()
              .put("type", "text")
              .put("text", text.substring(INSTRUCTION.length() + 1));
          ((ObjectNode) messages.get(0)).set("content", ordered);
          body = JSON.writeValueAsBytes(root);
        }
      }
    }
    return body;
  }
}
