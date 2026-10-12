package dev.mikoto2000.rei.llm;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
/** Canonicalizes only supplied definitions. Callback authority and context are delegated unchanged. */
public final class StableToolDefinitions {
 private static final ObjectMapper MAPPER=new ObjectMapper()
     .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
     .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
     .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private StableToolDefinitions() {}
 public static Prompt prompt(Prompt prompt) {
  if(!(prompt.getOptions() instanceof ToolCallingChatOptions options)||options.getToolCallbacks()==null||options.getToolCallbacks().isEmpty())return prompt;
  return new Prompt(prompt.getInstructions(),options.mutate().toolCallbacks(callbacks(options.getToolCallbacks())).build());
 }
 public static List<ToolCallback> callbacks(List<ToolCallback> callbacks) {
  return callbacks.stream().sorted(Comparator.comparing(c->c.getToolDefinition().name())).map(StableToolDefinitions::canonical).toList();
 }
 private static ToolCallback canonical(ToolCallback callback) {
  var original=callback.getToolDefinition();String schema=original.inputSchema();
  try {schema=MAPPER.writeValueAsString(ordered(MAPPER.readTree(schema)));}catch(Exception invalid){return callback;}
  if(schema.equals(original.inputSchema()))return callback;
  var definition=ToolDefinition.builder().name(original.name()).description(original.description()).inputSchema(schema).build();
  return dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.preservePolicy(callback,new ToolCallback() {
   public ToolDefinition getToolDefinition(){return definition;}
   public ToolMetadata getToolMetadata(){return callback.getToolMetadata();}
   public String call(String input){return callback.call(input);}
   public String call(String input,org.springframework.ai.chat.model.ToolContext context){return callback.call(input,context);}
  });
 }
 private static JsonNode ordered(JsonNode node) {
  if(node==null)return MAPPER.nullNode();
  if(node.isObject()){var result=MAPPER.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);for(var name:names)result.set(name,ordered(node.get(name)));return result;}
  if(node.isArray()){var result=MAPPER.createArrayNode();node.forEach(value->result.add(ordered(value)));return result;}
  return node;
 }
}
