package dev.mikoto2000.rei.core.dependency;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;

/** A fixed scalar comparison, never an executable expression. */
record HttpJsonCondition(int status,String pointer,JsonNode value,dev.mikoto2000.rei.core.predicate.DeclarativePredicate predicate) {
  HttpJsonCondition(int status,String pointer,JsonNode value){this(status,pointer,value,null);}
  private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder(
      JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(64).build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  static HttpJsonCondition parse(String text) {
    try {
      if(text==null||text.length()>4096)throw new IllegalArgumentException();
      var node=JSON.readTree(text);
      if(node==null||!node.isObject()||!node.has("status"))throw new IllegalArgumentException();
      var code=node.get("status");if(!code.isIntegralNumber()||!code.canConvertToInt()||code.intValue()<100||code.intValue()>599)throw new IllegalArgumentException();
      if(node.has("predicate")){if(node.size()!=2)throw new IllegalArgumentException();return new HttpJsonCondition(code.intValue(),null,null,dev.mikoto2000.rei.core.predicate.DeclarativePredicate.parse(node.get("predicate").toString()));}
      if(node.size()!=3||!node.has("pointer")||!node.has("value"))throw new IllegalArgumentException();
      var status=node.get("status");var pointer=node.get("pointer");var value=node.get("value");
      if(!status.isIntegralNumber()||!status.canConvertToInt()||status.intValue()<100||status.intValue()>599||!pointer.isTextual()||!value.isValueNode())throw new IllegalArgumentException();
      String path=pointer.textValue();
      if(path.length()>256||!path.startsWith("/")||path.chars().filter(c->c=='/').count()>16||path.codePoints().anyMatch(Character::isISOControl)||path.matches(".*~(?![01]).*"))throw new IllegalArgumentException();
      JsonPointer.compile(path);
      if(value.toString().length()>1024)throw new IllegalArgumentException();
      return new HttpJsonCondition(status.intValue(),path,value);
    }catch(java.io.IOException|IllegalArgumentException error){throw new IllegalArgumentException("Require bounded {status:100..599,pointer:/path,value:scalar} JSON");}
  }
  boolean matches(byte[] body)throws java.io.IOException {
    var node=JSON.readTree(body);if(node==null)throw new java.io.IOException("JSON unavailable");
    var actual=node.at(pointer);if(actual.isMissingNode()||!actual.isValueNode())return false;
    return actual.isNumber()&&value.isNumber()?actual.decimalValue().compareTo(value.decimalValue())==0:actual.equals(value);
  }
}
