package dev.mikoto2000.rei.goal;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

/** A bounded typed scalar predicate, never an executable completion claim. */
record JsonFileGoalCondition(String pointer,String expectedJson) {
  private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder(
      JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(64).build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  static JsonFileGoalCondition parse(String pointer,String expectedJson) {
    try {
      if(pointer==null||pointer.length()>256||!pointer.startsWith("/")||pointer.chars().filter(c->c=='/').count()>16
          ||pointer.codePoints().anyMatch(Character::isISOControl)||pointer.matches(".*~(?![01]).*"))throw new IllegalArgumentException();
      JsonPointer.compile(pointer);
      if(expectedJson==null||expectedJson.length()>1024)throw new IllegalArgumentException();
      var expected=JSON.readTree(expectedJson);
      if(expected==null||!expected.isValueNode())throw new IllegalArgumentException();
      return new JsonFileGoalCondition(pointer,expected.toString());
    }catch(java.io.IOException|IllegalArgumentException invalid){throw new IllegalArgumentException("Require a bounded JSON Pointer and scalar expectedJson");}
  }
  boolean matches(byte[] body)throws java.io.IOException {
    String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
    var document=JSON.readTree(text);if(document==null)throw new java.io.IOException("JSON unavailable");
    var actual=document.at(pointer);var expected=JSON.readTree(expectedJson);
    if(actual.isMissingNode()||!actual.isValueNode())return false;
    return actual.isNumber()&&expected.isNumber()?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected);
  }
  static java.util.List<GoalRepository.FileCriterion> parseCriteria(String text) {
    try {
      var items=JSON.readerFor(GoalRepository.FileCriterion[].class).<GoalRepository.FileCriterion[]>readValue(text);
      if(items==null)throw new IllegalArgumentException();
      return java.util.Arrays.asList(items);
    }catch(java.io.IOException|IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid bounded file criteria JSON");}
  }
}
