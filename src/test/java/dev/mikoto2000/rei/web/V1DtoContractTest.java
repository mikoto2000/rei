package dev.mikoto2000.rei.web;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Fixed public field names: expectations do not derive from Java reflection or internal entities. */
class V1DtoContractTest {
  private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
  @Test void phaseTwoThroughFourJsonFieldsRemainCompatible() throws Exception {
    fields(new FeedResponse(1,"url","title","name",true),"id url title displayName enabled");
    fields(new SkillResponse("name","description",true,"instructions"),"name description enabled instructions");
    fields(new ProfileResponse(0,null,null,Map.of(),Map.of()),"total first last countsByType durationsByType");
    fields(new ProfileResponse.DurationResponse(1,2,3,4,5),"count totalMillis minMillis maxMillis averageMillis");
    fields(new SearchResponse("query",List.of(),List.of()),"query vectorResults webResults");
    fields(new SearchResponse.VectorHit("id","source",0,0.9,"snippet"),"docId source chunkIndex score snippet");
    fields(new SearchResponse.WebHit("title","url","snippet","date","content",false),"title url snippet publishedAt content truncated");
    fields(new BriefingResponse("date","overview",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),
        "date overview events openTasks overdueTasks relatedDocuments feedItems interestUpdates cautionPoints nextActions");
    fields(new BriefingResponse.EventResponse("id","summary","start","end","location","status"),"id summary start end location status");
    fields(new BriefingResponse.TaskResponse(1,"title",null,1,"OPEN"),"id title dueDate priority status");
    fields(new BriefingResponse.ArticleResponse(1,"title","url",null,"feed"),"id title url publishedAt feedName");
    fields(new BackgroundRunController.AcceptedRunResponse("run"),"runId");
    fields(new ReminderResponse(1,"message","AT_TIME","date",null,null,false),"id message type remindAt targetAt minutesBefore notified");
    fields(new InterestResponse(1,"topic","reason","query","summary",List.of(),"date"),"id topic reason searchQuery summary sourceUrls createdAt");
    fields(new MemoryResponse("id","content","KNOWLEDGE","LONG_TERM","ACTIVE",0.8,null,"created","updated"),"id content type scope status confidence expiresAt createdAt updatedAt");
    fields(new StatefulController.SkillReloadResponse(0),"count");
  }
  private void fields(Object dto,String names) throws Exception {
    var tree=json.readTree(json.writeValueAsString(dto));
    assertThat(tree.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(names.split(" "));
    assertThat(tree.toString()).doesNotContain("@class","repository","projectRoot","skillFile","stackTrace");
  }
}
