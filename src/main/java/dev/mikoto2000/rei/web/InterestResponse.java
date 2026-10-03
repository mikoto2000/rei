package dev.mikoto2000.rei.web;
import java.util.List;
public record InterestResponse(long id,String topic,String reason,String searchQuery,String summary,List<String> sourceUrls,String createdAt) {
  public static InterestResponse from(dev.mikoto2000.rei.interest.InterestUpdate i) { return new InterestResponse(i.id(),i.topic(),i.reason(),i.searchQuery(),i.summary(),i.sourceUrls(),i.createdAt().toString()); }
}
