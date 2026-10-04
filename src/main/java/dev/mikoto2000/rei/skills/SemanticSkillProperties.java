package dev.mikoto2000.rei.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.skills.semantic")
public record SemanticSkillProperties(boolean enabled,int maxSkills,double minimumSimilarity,int failureBackoffSeconds) {
  public SemanticSkillProperties {
    if(maxSkills==0)maxSkills=64;
    if(minimumSimilarity==0)minimumSimilarity=.55;
    if(failureBackoffSeconds==0)failureBackoffSeconds=30;
    if(maxSkills<1 || maxSkills>256 || !Double.isFinite(minimumSimilarity) || minimumSimilarity<=0 || minimumSimilarity>1
        || failureBackoffSeconds<1 || failureBackoffSeconds>3600)throw new IllegalArgumentException("Invalid semantic skill limits");
  }
}
