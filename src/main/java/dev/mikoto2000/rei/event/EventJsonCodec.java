package dev.mikoto2000.rei.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The same typed wire format as the legacy project JSONL store. */
public final class EventJsonCodec {
  private EventJsonCodec(){}
  @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="_payloadType") private interface PayloadType {}
  public static ObjectMapper mapper(){
    var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);mapper.addMixIn(AgentEventPayload.class,PayloadType.class);
    for(Class<?> type:AgentEventPayload.class.getPermittedSubclasses())mapper.registerSubtypes(new NamedType(type,type.getSimpleName()));
    return mapper;
  }
}
