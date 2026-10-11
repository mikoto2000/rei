package dev.mikoto2000.rei.slack;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;

/** Returns an envelope ID only after durable admission; raw payloads are never logged. */
public final class SlackEnvelopeReceiver {
  private final SlackRuntime runtime;private final String team;
  private final SlackAgentService collaboration;
  private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65536).build()).build());
  public SlackEnvelopeReceiver(SlackRuntime runtime,String team){this(runtime,team,null);}
  public SlackEnvelopeReceiver(SlackRuntime runtime,String team,SlackAgentService collaboration){this.runtime=runtime;this.team=team;this.collaboration=collaboration;}
  public String receive(String raw) {
    if(raw==null||raw.length()>262144)return null;
    try {
      var envelope=json.readTree(raw);if(!"events_api".equals(envelope.path("type").asText()))return null;
      String id=value(envelope,"envelope_id");if(id==null||id.isBlank())return null;
      var payload=envelope.path("payload");var event=payload.path("event");
      if(!team.equals(value(payload,"team_id"))||!"event_callback".equals(value(payload,"type")))return id;
      String type=value(event,"type");if(!"message".equals(type)&&!"app_mention".equals(type))return id;
      var message=new SlackMessage(value(payload,"event_id"),team,value(event,"channel"),value(event,"ts"),value(event,"thread_ts"),
          value(event,"user"),value(event,"bot_id"),value(event,"subtype"),type,value(event,"channel_type"),value(event,"text"));
      if(message.botId()!=null){if(collaboration!=null)collaboration.receive(message);}
      else runtime.accept(message);
      return id;
    }catch(Exception failed){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Slack envelope not acknowledged: validation or persistence unavailable (details omitted)");return null;}
  }
  private static String value(JsonNode node,String key){var v=node.get(key);return v!=null&&v.isTextual()?v.asText():null;}
}
