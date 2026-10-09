package dev.mikoto2000.rei.externalagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Canonical, bounded requirements over existing real Project paths. No silent truncation. */
public final class ImplementationSpecificationValidator {
  private static final ObjectMapper JSON=new ObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true)
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
  public record Validated(ImplementationSpecification specification,String canonical,String sha256,String task) {}
  private ImplementationSpecificationValidator() {}
  public static Validated validate(Path directory,ImplementationSpecification input) {
    if(input==null)throw new IllegalArgumentException("objective, instructions, target, allowedPaths and acceptanceCriteria required");
    if(input.schemaVersion()!=1 || !"REPLACE_EXISTING_TEXT".equals(input.changeMode()))throw new IllegalArgumentException("Only schema 1 / REPLACE_EXISTING_TEXT is supported");
    String objective=text(input.objective(),500,"objective");
    var instructions=texts(input.instructions(),1,16,1000,"instructions");
    var constraints=texts(input.constraints()==null?List.of():input.constraints(),0,16,500,"constraints");
    String targetText=text(input.target(),1024,"target");
    var paths=texts(input.allowedPaths(),1,32,1024,"allowedPaths");
    if(input.acceptanceCriteria()==null || input.acceptanceCriteria().isEmpty() || input.acceptanceCriteria().size()>16)throw new IllegalArgumentException("1 to 16 acceptanceCriteria required");
    var ids=new HashSet<String>();var criteria=new ArrayList<ImplementationSpecification.AcceptanceCriterion>();
    for(var criterion:input.acceptanceCriteria()) {
      if(criterion==null)throw new IllegalArgumentException("Missing acceptance criterion");
      String id=text(criterion.id(),64,"criterion ID");if(!id.matches("[A-Za-z0-9_-]+") || !ids.add(id))throw new IllegalArgumentException("Unique criterion IDs required");
      criteria.add(new ImplementationSpecification.AcceptanceCriterion(id,text(criterion.description(),500,"acceptance criterion")));
    }
    var references=new ArrayList<ImplementationSpecification.RequirementReference>();
    if(input.references()!=null) {
      if(input.references().size()>16)throw new IllegalArgumentException("At most 16 references");
      for(var reference:input.references()) {
        if(reference==null || !Set.of("USER_EXPLICIT","USER_CONFIRMED","CODE_FACT","DESIGN_DOCUMENT").contains(reference.sourceType()))throw new IllegalArgumentException("Known reference source required; references never authorize execution");
        references.add(new ImplementationSpecification.RequirementReference(reference.sourceType(),text(reference.locator(),1024,"reference locator"),text(reference.excerpt(),500,"reference excerpt")));
      }
    }
    try {
      Path root=directory.toRealPath(),target=ExternalAgentRequest.resolveTarget(root,targetText);
      var normalized=new TreeSet<String>();
      for(String name:paths) {
        Path allowed=ExternalAgentRequest.resolveTarget(root,name);
        if(!allowed.startsWith(target) || !Files.isDirectory(target)&&!allowed.equals(target))throw new IllegalArgumentException("allowedPaths must be inside target");
        if(ExternalAgentSourceSnapshot.excluded(root.relativize(allowed)))throw new IllegalArgumentException("Excluded allowed path");
        normalized.add(relative(root,allowed));
      }
      // Reuse all source limits, UTF-8 checks and link/junction escape checks before authorization.
      ExternalAgentSourceSnapshot.snapshot(root,target,()->false,System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos());
      var specification=new ImplementationSpecification(1,objective,instructions,relative(root,target),List.copyOf(normalized),constraints,List.copyOf(criteria),List.copyOf(references),"REPLACE_EXISTING_TEXT");
      String task="Approved implementation objective:\n"+objective+"\nInstructions:\n"+String.join("\n",instructions)+"\nConstraints:\n"+String.join("\n",constraints);
      if(task.length()>4000)throw new IllegalArgumentException("Required task exceeds existing 4000 character limit; clarify a smaller request");
      String canonical=JSON.writeValueAsString(specification);if(canonical.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("Specification exceeds 32 KiB");
      return new Validated(specification,canonical,ImplementationProposal.sha256(canonical.getBytes(StandardCharsets.UTF_8)),task);
    }catch(java.io.IOException error){throw new IllegalArgumentException("Target exceeds source limits or cannot be safely read",error);}
  }
  static String serialize(Object value) {try{return JSON.writeValueAsString(value);}catch(Exception error){throw new IllegalArgumentException("Cannot serialize implementation request",error);}}
  static String hash(Object value){return ImplementationProposal.sha256(serialize(value).getBytes(StandardCharsets.UTF_8));}
  private static String relative(Path root,Path path){String result=root.relativize(path).toString().replace('\\','/');return result.isEmpty()?".":result;}
  private static String text(String value,int max,String label) {
    if(value==null || value.isBlank() || value.length()>max || value.indexOf(0)>=0)throw new IllegalArgumentException(label+" is required and must be at most "+max+" characters");return value.strip();
  }
  private static List<String> texts(List<String> values,int min,int max,int length,String label) {
    if(values==null || values.size()<min || values.size()>max)throw new IllegalArgumentException(label+" requires "+min+" to "+max+" entries");return values.stream().map(value->text(value,length,label)).toList();
  }
}
