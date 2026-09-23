package dev.mikoto2000.rei.activity;
import java.util.List;
public record ClassificationDiagnostics(List<String> matchedRuleIds,String winningRuleId,String source,
    ActivityFieldConfidence confidence,boolean classificationUsable,boolean visionRequired,String visionRequiredReason,
    List<String> unknownReasons,EntertainmentDisposition entertainmentDisposition,double entertainmentConfidence,
    String matchedEntertainmentRuleId,String entertainmentSource,String entertainmentReason) {}
