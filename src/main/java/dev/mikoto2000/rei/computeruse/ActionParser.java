package dev.mikoto2000.rei.computeruse;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import static dev.mikoto2000.rei.computeruse.ComputerAction.*;

/** Strict decoding, followed by action-specific validation. No markdown, coercion, or action lists. */
public final class ActionParser {
  private final ObjectMapper mapper = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> FIELDS = Set.of("action", "risk", "target", "confidence",
      "text", "key", "amount", "millis", "reason");

  public ComputerAction parse(String json, CapturedScreen screen) {
    try {
      if (json == null || json.isBlank()) throw new InvalidComputerDecision("No response text");
      if (json.length() > 30000) throw new InvalidComputerDecision("Response text exceeds 30000 characters: " + json.length());
      JsonNode node = mapper.readTree(json);
      fields(node, FIELDS);
      String type = string(node, "action");
      Risk risk;
      try { risk = Risk.valueOf(string(node, "risk")); }
      catch (IllegalArgumentException invalid) { throw new InvalidComputerDecision("Unknown or missing risk"); }
      Set<String> used = switch (type) {
        case "CLICK", "DOUBLE_CLICK" -> Set.of("target", "confidence");
        case "TYPE_TEXT" -> Set.of("text");
        case "PRESS_KEY" -> Set.of("key");
        case "SCROLL" -> Set.of("amount");
        case "WAIT" -> Set.of("millis");
        case "DONE", "FAILED", "UNCERTAIN" -> Set.of("reason");
        default -> throw new InvalidComputerDecision("Unknown action");
      };
      // An explanation is metadata, not an additional input operation.
      if (!node.get("reason").isNull()) ActionValidator.text(string(node, "reason"), 300);
      for (String field : FIELDS) {
        if (!field.equals("action") && !field.equals("risk") && !field.equals("reason") && !used.contains(field) && !node.get(field).isNull())
          throw new InvalidComputerDecision("Field must be null for this action: " + field);
      }
      ComputerAction action = switch (type) {
        case "CLICK" -> new Click(target(node), number(node, "confidence"), risk);
        case "DOUBLE_CLICK" -> new DoubleClick(target(node), number(node, "confidence"), risk);
        case "TYPE_TEXT" -> new TypeText(string(node, "text"), risk);
        case "PRESS_KEY" -> new PressKey(string(node, "key"), risk);
        case "SCROLL" -> new Scroll(integer(node, "amount"), risk);
        case "WAIT" -> new Wait(integer(node, "millis"));
        case "DONE" -> new Done(string(node, "reason"));
        case "FAILED" -> new Failed(string(node, "reason"));
        case "UNCERTAIN" -> new Uncertain(string(node, "reason"));
        default -> throw new InvalidComputerDecision("Unknown action");
      };
      ActionValidator.validate(action, screen);
      return action;
    } catch (InvalidComputerDecision error) {
      throw error;
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new InvalidComputerDecision("Malformed JSON, duplicate field, or trailing content");
    }
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) throw new InvalidComputerDecision("Expected object");
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw new InvalidComputerDecision("Unexpected or missing fields");
  }
  private static String string(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) throw new InvalidComputerDecision("Expected string: " + name);
    return value.textValue();
  }
  private static int integer(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new InvalidComputerDecision("Expected integer: " + name);
    return value.intValue();
  }
  private static double number(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isNumber()) throw new InvalidComputerDecision("Expected number: " + name);
    return value.doubleValue();
  }
  private static Target target(JsonNode node) {
    JsonNode value = node.get("target");
    fields(value, Set.of("centerX", "centerY", "description"));
    return new Target(integer(value, "centerX"), integer(value, "centerY"), string(value, "description"));
  }
}
