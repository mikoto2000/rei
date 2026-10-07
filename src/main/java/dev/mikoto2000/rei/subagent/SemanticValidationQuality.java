package dev.mikoto2000.rei.subagent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Explicit evaluation only. Fixture labels and judge agreement are not correctness proofs. */
public final class SemanticValidationQuality {
  private SemanticValidationQuality(){}
  public enum Decision { ACCEPT, REJECT, ABSTAIN }
  public record Observation(String tool,String arguments,String output){public Observation{bounded(tool,100);bounded(arguments,16384);bounded(output,16384);}}
  public record Case(String id,String category,String task,String answer,List<Observation> observations,Decision expected){
    public Case{bounded(id,128);bounded(category,128);bounded(task,8192);bounded(answer,32768);observations=List.copyOf(observations);if(observations.size()>16||expected==null)throw new IllegalArgumentException("Bounded labelled evidence required");}
  }
  @FunctionalInterface public interface Judge { Decision judge(Case fixture); }
  public record Row(String id,String category,Decision expected,Decision primary,Decision secondary){}
  public record Metrics(int truePositive,int falsePositive,int falseNegative,int trueNegative,int abstentions,int uncertainDecisions,double coverage){}
  public record Report(String primaryProvider,String secondaryProvider,List<Row> rows,Metrics primary,Metrics secondary,Double agreement,boolean truthVerified){}
  public static Report evaluate(List<Case> fixtures,String primaryProvider,Judge primary,String secondaryProvider,Judge secondary){
    bounded(primaryProvider,128);Objects.requireNonNull(primary);fixtures=List.copyOf(fixtures);if(fixtures.isEmpty()||fixtures.size()>128||fixtures.stream().map(Case::id).distinct().count()!=fixtures.size())throw new IllegalArgumentException("Unique bounded fixture set required");
    if((secondaryProvider==null)!=(secondary==null))throw new IllegalArgumentException("Secondary identity and judge required together");if(secondaryProvider!=null)bounded(secondaryProvider,128);
    long started=System.nanoTime();var rows=new ArrayList<Row>();int agreed=0;
    for(var fixture:fixtures){active(started);var a=Objects.requireNonNull(primary.judge(fixture));active(started);var b=secondary==null?null:Objects.requireNonNull(secondary.judge(fixture));active(started);if(a==b)agreed++;rows.add(new Row(fixture.id(),fixture.category(),fixture.expected(),a,b));}
    var saved=List.copyOf(rows);return new Report(primaryProvider,secondaryProvider,saved,metrics(saved,false),secondary==null?null:metrics(saved,true),secondary==null?null:(double)agreed/rows.size(),false);
  }
  private static Metrics metrics(List<Row> rows,boolean secondary){int tp=0,fp=0,fn=0,tn=0,abstain=0,uncertain=0;for(var row:rows){var actual=secondary?row.secondary():row.primary();if(actual==Decision.ABSTAIN){abstain++;continue;}if(row.expected()==Decision.ABSTAIN){uncertain++;continue;}if(actual==Decision.REJECT){if(row.expected()==Decision.REJECT)tp++;else fp++;}else if(row.expected()==Decision.REJECT)fn++;else tn++;}return new Metrics(tp,fp,fn,tn,abstain,uncertain,(double)(rows.size()-abstain)/rows.size());}
  /** Uses the production validator with one bounded, tool-free call and an explicit shared reservation. */
  public static Judge model(ChatModel model,ToolCallingChatOptions options,AgentRunContext owner,OutputLimitRunBudget.LlmCallReservation reservation,Runnable check){
    Objects.requireNonNull(model);Objects.requireNonNull(options);Objects.requireNonNull(owner);Objects.requireNonNull(check);
    return fixture->{check.run();var evidence=new SubAgentEvidence();for(var observation:fixture.observations())evidence.capture(observation.tool(),observation.arguments(),observation.output());
      var definition=new SubAgentDefinition("evaluation","Evaluation","Semantic evaluation","Evaluate original task",List.of(),null,1,Duration.ofSeconds(10),owner.projectRoot());
      try{new SubAgentSemanticValidator().validate(model,new Prompt(fixture.task(),options),definition,fixture.answer(),evidence,new AtomicInteger(1),owner,check,reservation).block(Duration.ofSeconds(10));check.run();return Decision.ACCEPT;}
      catch(SubAgentValidationException invalid){return invalid.errors().stream().anyMatch(error->error.message().startsWith("Semantic validation: "))?Decision.REJECT:Decision.ABSTAIN;}
    };
  }
  private static void active(long started){RunCancellation.propagate(null);if(System.nanoTime()-started>Duration.ofSeconds(30).toNanos())throw new IllegalStateException("Evaluation deadline exceeded");}
  private static void bounded(String value,int limit){if(value==null||value.isBlank()||value.length()>limit)throw new IllegalArgumentException("Bounded nonblank evaluation text required");}
}
