package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frames are parsed independently; only terminal response output can become a result. */
public final class PlanSseAssembler {
  public enum State {
    COMPLETED,
    INCOMPLETE,
    FAILED,
    OUTCOME_UNKNOWN
  }

  public record Result(
      State state,
      String text,
      Integer totalTokens,
      String responseId,
      ChatGptException.Code error) {}

  private final ObjectMapper json =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public Result read(InputStream input, int maxBytes) {
    var line = new ByteArrayOutputStream();
    var data = new StringBuilder();
    String event = "";
    int received = 0;
    boolean cr = false;
    try {
      for (int b; (b = input.read()) != -1; ) {
        if (++received > maxBytes) throw ChatGptException.of(RECEIVE_LIMIT);
        if (cr && b == '\n') {
          cr = false;
          continue;
        }
        cr = false;
        if (b == '\n' || b == '\r') {
          cr = b == '\r';
          String text = decode(line.toByteArray());
          line.reset();
          if (text.isEmpty()) {
            if (!data.isEmpty()) {
              var result = parse(event, data.toString());
              if (result != null) return result;
            }
            data.setLength(0);
            event = "";
          } else if (text.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(text.substring(5).replaceFirst("^ ", ""));
          } else if (text.startsWith("event:")) event = text.substring(6).strip();
        } else line.write(b);
      }
      return new Result(State.OUTCOME_UNKNOWN, "", null, null, null);
    } catch (ChatGptException e) {
      throw e;
    } catch (IOException e) {
      return new Result(State.OUTCOME_UNKNOWN, "", null, null, TRANSPORT);
    }
  }

  private String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  private Result parse(String event, String data) {
    try {
      var node = json.readTree(data);
      String type = node.path("type").asText();
      if (!event.isEmpty() && !event.equals(type)) throw ChatGptException.of(PROTOCOL);
      var response = node.path("response");
      if (type.equals("response.failed") || type.equals("error")) {
        String code =
            type.equals("error")
                ? node.path("code").asText()
                : response.path("error").path("code").asText();
        return new Result(State.FAILED, "", usage(response), null, classify(code, 0));
      }
      if (type.equals("response.incomplete"))
        return new Result(State.INCOMPLETE, "", usage(response), null, null);
      if (!type.equals("response.completed")) return null;
      if (!response.path("status").asText().equals("completed")
          || !response.path("output").isArray()) throw ChatGptException.of(PROTOCOL);
      var text = new StringBuilder();
      for (var item : response.path("output")) {
        String kind = item.path("type").asText();
        if (kind.equals("reasoning")) continue;
        if (!kind.equals("message") || !item.path("content").isArray())
          throw ChatGptException.of(PROTOCOL);
        for (var content : item.path("content")) {
          if (!content.path("type").asText().equals("output_text")
              || !content.path("text").isTextual()) throw ChatGptException.of(PROTOCOL);
          text.append(content.path("text").asText());
        }
      }
      return new Result(
          State.COMPLETED,
          text.toString(),
          usage(response),
          response.path("id").asText(null),
          null);
    } catch (ChatGptException e) {
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  private Integer usage(JsonNode response) {
    var value = response.path("usage").path("total_tokens");
    return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
        ? value.intValue()
        : null;
  }

  static ChatGptException.Code classify(String code, int status) {
    return switch (code) {
      case "subscription_sharing_usage_limit_exceeded" -> USAGE_LIMIT;
      case "subscription_sharing_usage_unavailable" -> USAGE_UNAVAILABLE;
      case "model_not_found", "model_not_available" -> MODEL_UNAVAILABLE;
      case "subscription_sharing_invalid_user" -> AUTH_INVALID;
      case "subscription_sharing_user_not_eligible",
          "chatpass_v2_scope_not_authorized",
          "chatpass_v2_invalid_authorization_context" ->
          PERMISSION_DENIED;
      default ->
          switch (status) {
            case 401 -> AUTH_INVALID;
            case 403 -> PERMISSION_DENIED;
            case 429 -> USAGE_LIMIT;
            case 503 -> ROUTE_UNAVAILABLE;
            default -> PROTOCOL;
          };
    };
  }
}
