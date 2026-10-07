package dev.mikoto2000.rei.core.predicate;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.google.re2j.Pattern;

/** Fixed, typed observations only. Missing data remains UNKNOWN through NOT and NE. */
public final class DeclarativePredicate {
  public enum Result { SATISFIED, UNSATISFIED, UNKNOWN }
  private record Node(String op,String pointer,JsonNode value,List<Node> args,Pattern pattern) {}
  private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder(
      JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(64).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  private final Node root;private final String canonical;
  private DeclarativePredicate(Node root,String canonical){this.root=root;this.canonical=canonical;}
  public String json(){return canonical;}
  public static DeclarativePredicate parse(String text) {
    try {
      if(text==null || text.length()>4096)throw invalid();var document=JSON.readTree(text);
      if(document==null || !document.isObject() || document.size()!=2 || !document.path("version").isIntegralNumber() || !document.path("version").canConvertToInt() || document.path("version").intValue()!=1 || !document.has("expression"))throw invalid();
      var root=node(document.get("expression"),1,new int[]{0});return new DeclarativePredicate(root,document.toString());
    }catch(java.io.IOException | RuntimeException error){throw invalid();}
  }
  private static Node node(JsonNode json,int depth,int[] count) {
    if(depth>8 || ++count[0]>32 || json==null || !json.isObject() || !json.path("op").isTextual())throw invalid();String op=json.get("op").textValue();
    if(Set.of("AND","OR","NOT").contains(op)) {
      var args=json.path("args");if(json.size()!=2 || !args.isArray() || args.isEmpty() || args.size()>8 || op.equals("NOT") && args.size()!=1)throw invalid();
      var children=new ArrayList<Node>();for(var child:args)children.add(node(child,depth+1,count));return new Node(op,null,null,List.copyOf(children),null);
    }
    if(op.equals("EXISTS")){if(json.size()!=2 || !json.path("pointer").isTextual())throw invalid();String pointer=json.path("pointer").textValue();validatePointer(pointer);return new Node(op,pointer,null,List.of(),null);}
    if(!Set.of("EQ","NE","GT","GTE","LT","LTE","MATCH").contains(op) || json.size()!=3 || !json.path("pointer").isTextual() || !json.has("value"))throw invalid();
    String pointer=json.path("pointer").textValue();var value=json.get("value");
    validatePointer(pointer);if(!value.isValueNode() || value.toString().length()>1024)throw invalid();
    if(Set.of("GT","GTE","LT","LTE").contains(op) && !value.isNumber() && !value.isTextual())throw invalid();
    Pattern pattern=null;
    if(op.equals("MATCH")){if(!value.isTextual() || value.textValue().length()>256 || value.textValue().contains("{") || value.textValue().contains("}"))throw invalid();pattern=Pattern.compile(value.textValue());if(pattern.programSize()>512)throw invalid();}
    return new Node(op,pointer,value,List.of(),pattern);
  }
  public Result evaluate(byte[] body){return evaluate(body,System.nanoTime()+250_000_000L);}
  public Result evaluate(byte[] body,long deadline) {
    if(body==null || body.length>65536 || expired(deadline))return Result.UNKNOWN;
    try {
      String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
      var document=JSON.readTree(text);if(document==null || expired(deadline))return Result.UNKNOWN;
      var result=evaluate(root,document,deadline);return expired(deadline)?Result.UNKNOWN:result;
    }catch(java.io.IOException | RuntimeException error){return Result.UNKNOWN;}
  }
  private Result evaluate(Node node,JsonNode document,long deadline) {
    if(expired(deadline))return Result.UNKNOWN;
    if(node.op().equals("NOT")){return switch(evaluate(node.args().getFirst(),document,deadline)){case SATISFIED->Result.UNSATISFIED;case UNSATISFIED->Result.SATISFIED;case UNKNOWN->Result.UNKNOWN;};}
    if(Set.of("AND","OR").contains(node.op())) {
      boolean unknown=false;for(var child:node.args()){var observed=evaluate(child,document,deadline);unknown|=observed==Result.UNKNOWN;
        if(node.op().equals("AND") && observed==Result.UNSATISFIED)return Result.UNSATISFIED;if(node.op().equals("OR") && observed==Result.SATISFIED)return Result.SATISFIED;}
      return unknown?Result.UNKNOWN:node.op().equals("AND")?Result.SATISFIED:Result.UNSATISFIED;
    }
    var actual=document.at(node.pointer());if(node.op().equals("EXISTS"))return truth(!actual.isMissingNode());var expected=node.value();if(actual.isMissingNode() || !actual.isValueNode())return Result.UNKNOWN;
    if(actual.isTextual() && actual.textValue().length()>4096)return Result.UNKNOWN;
    if(node.op().equals("MATCH"))return actual.isTextual()?truth(node.pattern().matcher(actual.textValue()).matches()):Result.UNKNOWN;
    int order;
    if(actual.isNumber() && expected.isNumber())order=actual.decimalValue().compareTo(expected.decimalValue());
    else if(actual.isTextual() && expected.isTextual())order=actual.textValue().compareTo(expected.textValue());
    else if((actual.isBoolean() && expected.isBoolean() || actual.isNull() && expected.isNull()) && Set.of("EQ","NE").contains(node.op()))order=actual.equals(expected)?0:1;
    else return Result.UNKNOWN;
    return truth(switch(node.op()){case "EQ"->order==0;case "NE"->order!=0;case "GT"->order>0;case "GTE"->order>=0;case "LT"->order<0;case "LTE"->order<=0;default->false;});
  }
  private static boolean expired(long deadline){return Thread.currentThread().isInterrupted() || System.nanoTime()-deadline>=0;}
  private static void validatePointer(String pointer){if(pointer.length()>256 || !pointer.startsWith("/") || pointer.chars().filter(c->c=='/').count()>16 || pointer.codePoints().anyMatch(Character::isISOControl) || pointer.matches(".*~(?![01]).*"))throw invalid();JsonPointer.compile(pointer);}
  private static Result truth(boolean value){return value?Result.SATISFIED:Result.UNSATISFIED;}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid bounded version 1 declarative predicate");}
}
