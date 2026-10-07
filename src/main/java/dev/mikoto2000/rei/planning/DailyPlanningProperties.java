package dev.mikoto2000.rei.planning;
import java.time.*;
import java.util.*;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.today")
@lombok.Getter @lombok.Setter
public class DailyPlanningProperties {
  private boolean enabled;
  private Set<String> projects=Set.of();
  private String zone="Asia/Tokyo";
  private int maxTasksPerProject=1000,maxItemsPerProject=128,collectionBudgetSeconds=5;
  private Duration staleAfter=Duration.ofDays(7);
  public void validate(){
    if(projects==null||projects.isEmpty()||projects.size()>16||projects.stream().anyMatch(id->id==null||!id.matches("[a-fA-F0-9-]{36}"))
        ||maxTasksPerProject<1||maxTasksPerProject>2000||maxItemsPerProject<1||maxItemsPerProject>256
        ||collectionBudgetSeconds<1||collectionBudgetSeconds>10||staleAfter==null||staleAfter.compareTo(Duration.ofHours(1))<0||staleAfter.compareTo(Duration.ofDays(366))>0)
      throw new IllegalArgumentException("Invalid Today allowlist or collection limits");
    try{ZoneId.of(zone);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid Today calendar zone");}
  }
}
