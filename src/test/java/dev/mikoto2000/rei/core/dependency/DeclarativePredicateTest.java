package dev.mikoto2000.rei.core.dependency;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import dev.mikoto2000.rei.core.predicate.DeclarativePredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativePredicateTest {
  String spec(String expression){return "{\"version\":1,\"expression\":"+expression+"}";}
  byte[] body(String text){return text.getBytes(StandardCharsets.UTF_8);}
  @Test void composesTypedNumericStringAndBooleanComparisons() {
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"AND\",\"args\":[{\"op\":\"GTE\",\"pointer\":\"/coverage\",\"value\":80},{\"op\":\"EQ\",\"pointer\":\"/status\",\"value\":\"passed\"}]}"));
    assertEquals(DeclarativePredicate.Result.SATISFIED,predicate.evaluate(body("{\"coverage\":80.0,\"status\":\"passed\"}")));
    assertEquals(DeclarativePredicate.Result.UNSATISFIED,predicate.evaluate(body("{\"coverage\":79.99,\"status\":\"passed\"}")));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{\"coverage\":\"90\",\"status\":\"passed\"}")));
  }
  @Test void notAndInequalityNeverTurnMissingObservationIntoSuccess() {
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"NOT\",\"args\":[{\"op\":\"EQ\",\"pointer\":\"/missing\",\"value\":true}]}"));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{}")));
    var inequality=DeclarativePredicate.parse(spec("{\"op\":\"NE\",\"pointer\":\"/missing\",\"value\":null}"));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,inequality.evaluate(body("{}")));
    assertEquals(DeclarativePredicate.Result.UNSATISFIED,inequality.evaluate(body("{\"missing\":null}")));
  }
  @Test void rejectsSchemaVersionsUnknownFieldsCodeAndExcessiveDepth() {
    for(String invalid:new String[]{"{\"version\":2,\"expression\":{}}",spec("{\"op\":\"EQ\",\"pointer\":\"/a\",\"value\":1}").replace("\"version\":1","\"version\":4294967297"),spec("{\"op\":\"EVAL\",\"value\":\"exec()\"}"),spec("{\"op\":\"EQ\",\"pointer\":\"/a\",\"value\":1,\"code\":\"ignored?\"}"),spec("{\"op\":\"EQ\",\"pointer\":\"/a\",\"value\":1,\"value\":2}")})assertThrows(IllegalArgumentException.class,()->DeclarativePredicate.parse(invalid));
    String expression="{\"op\":\"EQ\",\"pointer\":\"/a\",\"value\":1}";for(int i=0;i<10;i++)expression="{\"op\":\"NOT\",\"args\":["+expression+"]}";
    String deep=spec(expression);assertThrows(IllegalArgumentException.class,()->DeclarativePredicate.parse(deep));
  }
  @Test void boundedRegexRejectsBackreferencesAndDoesNotBacktrackExponentially() {
    assertThrows(IllegalArgumentException.class,()->DeclarativePredicate.parse(spec("{\"op\":\"MATCH\",\"pointer\":\"/text\",\"value\":\"(a)\\\\1\"}")));
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"MATCH\",\"pointer\":\"/text\",\"value\":\"(a+)+$\"}"));
    assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(DeclarativePredicate.Result.UNSATISFIED,predicate.evaluate(body("{\"text\":\""+"a".repeat(4000)+"!\"}"))));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{\"text\":\""+"a".repeat(5000)+"\"}")));
  }
  @Test void cancellationAndExpiredDeadlineAreUnknownAndDoNotPublishSuccess() {
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"EQ\",\"pointer\":\"/a\",\"value\":1}"));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{\"a\":1}"),System.nanoTime()-1));
    Thread.currentThread().interrupt();try{assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{\"a\":1}")));}finally{Thread.interrupted();}
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(new byte[]{(byte)0xff}));
  }
  @Test void orUsesKnownTrueButCannotResolveUnknownWithFalseAndComparisonsKeepPrecision() {
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"OR\",\"args\":[{\"op\":\"EQ\",\"pointer\":\"/missing\",\"value\":true},{\"op\":\"GT\",\"pointer\":\"/n\",\"value\":1}]}"));
    assertEquals(DeclarativePredicate.Result.SATISFIED,predicate.evaluate(body("{\"n\":1.00000000000000001}")));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("{\"n\":1}")));
    for(String op:new String[]{"LT","LTE","NE"})assertEquals(DeclarativePredicate.Result.SATISFIED,DeclarativePredicate.parse(spec("{\"op\":\""+op+"\",\"pointer\":\"/n\",\"value\":2}")).evaluate(body("{\"n\":1}")));
    String leaf="{\"op\":\"EQ\",\"pointer\":\"/n\",\"value\":1}";
    String wide="{\"op\":\"AND\",\"args\":["+java.util.stream.IntStream.range(0,8).mapToObj(i->"{\"op\":\"OR\",\"args\":["+String.join(",",java.util.Collections.nCopies(4,leaf))+"]}").collect(java.util.stream.Collectors.joining(","))+"]}";
    assertThrows(IllegalArgumentException.class,()->DeclarativePredicate.parse(spec(wide)));
  }
  @Test void existsChecksObservedJsonPresenceIncludingExplicitNull() {
    var predicate=DeclarativePredicate.parse(spec("{\"op\":\"EXISTS\",\"pointer\":\"/value\"}"));
    assertEquals(DeclarativePredicate.Result.SATISFIED,predicate.evaluate(body("{\"value\":null}")));
    assertEquals(DeclarativePredicate.Result.UNSATISFIED,predicate.evaluate(body("{}")));
    assertEquals(DeclarativePredicate.Result.UNKNOWN,predicate.evaluate(body("not JSON")));
  }
}
