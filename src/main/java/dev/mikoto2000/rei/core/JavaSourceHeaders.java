package dev.mikoto2000.rei.core;

/** Locate the class body delimiter in an AST-bounded declaration, respecting literals/comments. */
final class JavaSourceHeaders {
  static int end(String source,int start,int end){
    if(start<0||end<start||end>source.length())return Math.max(0,start);
    int state=0,paren=0;
    for(int i=start;i<end;i++){
      char c=source.charAt(i),next=i+1<end?source.charAt(i+1):0;
      // Java translates Unicode escapes before lexing, including comments/literals.
      // An unprocessed header cannot safely identify its body delimiter in this case.
      if(c=='\\'&&next=='u')return start;
      if(state==1){if(c=='\n'||c=='\r')state=0;continue;}
      if(state==2){if(c=='*'&&next=='/'){state=0;i++;}continue;}
      if(state==5){if(c=='\\'){i++;continue;}if(c=='"'&&i+2<end&&source.startsWith("\"\"\"",i)){state=0;i+=2;}continue;}
      if(state==3||state==4){if(c=='\\'){i++;continue;}if(c==(state==3?'"':'\''))state=0;continue;}
      if(c=='/'&&next=='/'){state=1;i++;continue;}if(c=='/'&&next=='*'){state=2;i++;continue;}
      if(c=='"'){if(i+2<end&&source.startsWith("\"\"\"",i)){state=5;i+=2;}else state=3;continue;}if(c=='\''){state=4;continue;}
      if(c=='(')paren++;else if(c==')')paren--;else if(c=='{'&&paren==0)return i;
    }return start; // Never infer a header extending through the declaration body.
  }
}
