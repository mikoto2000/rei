package dev.mikoto2000.rei.externalagent;

import java.util.List;

/** Requirements are data; no field supplies authority or executable commands. */
public record ImplementationSpecification(int schemaVersion,String objective,List<String> instructions,String target,
    List<String> allowedPaths,List<String> constraints,List<AcceptanceCriterion> acceptanceCriteria,
    List<RequirementReference> references,String changeMode) {
  public record AcceptanceCriterion(String id,String description) {}
  public record RequirementReference(String sourceType,String locator,String excerpt) {}
}
