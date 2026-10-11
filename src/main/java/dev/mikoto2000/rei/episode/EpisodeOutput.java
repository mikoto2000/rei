package dev.mikoto2000.rei.episode;
import java.util.*;
public final class EpisodeOutput {
  public List<Episode> parse(String output,String session,String project,List<Episode> existing) {
    try {
      var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=json.readTree(output);
      var array=root.get("episodes");
      if(array==null||!array.isArray()||array.size()>50)throw new IllegalArgumentException("Invalid episodes output");
      var result=new ArrayList<Episode>();
      for(var row:array) {
        String target=row.path("id").asText();
        if(!target.isEmpty()&&existing.stream().noneMatch(e->e.id().equals(target)))throw new IllegalArgumentException("Unknown episode continuation");
        String id=target.isEmpty()?"ep_"+UUID.randomUUID():target;
        var claims=json.readerForListOf(Episode.Claim.class).<List<Episode.Claim>>readValue(row.get("claims"));
        result.add(new Episode(id,project,session,"rev_"+UUID.randomUUID(),row.path("occurredAt").asText(),row.path("endedAt").isTextual()?row.path("endedAt").asText():null,
            Episode.Status.valueOf(row.path("status").asText()),row.path("title").asText(),row.path("summary").asText(),claims,row.path("confidence").asDouble()));
      }
      return List.copyOf(result);
    } catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid episode output",e);}
  }
}
