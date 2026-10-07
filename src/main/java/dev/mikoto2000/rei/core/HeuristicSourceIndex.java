package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.util.*;
import com.google.re2j.Pattern;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Bounded lexical candidates. This deliberately does not implement a language/compiler resolver. */
final class HeuristicSourceIndex {
  record Parsed(String packageName,String status,List<RepositoryMapService.Symbol> symbols,List<String> imports) {}
  record Boundary(String root,String name,String kind) {}
  record Boundaries(List<Boundary> values,int bytes,List<String> warnings) {
    Boundary forPath(String path){return values.stream().filter(value->value.root().equals(".")||path.startsWith(value.root()+"/")).max(Comparator.comparingInt(value->value.root().length())).orElse(new Boundary(fallbackModule(path),"","UNKNOWN"));}
  }
  record Token(String value,boolean literal,int line) {}
  record Lexed(String masked,List<Token> tokens,boolean valid) {}
  private static final String ID="([\\p{L}_$][\\p{L}\\p{N}_$]*)";
  private static final Pattern TS_TYPE=Pattern.compile("^(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(class|interface|enum|type)\\s+"+ID);
  private static final Pattern TS_FN=Pattern.compile("^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+"+ID);
  private static final Pattern TS_ARROW=Pattern.compile("^(?:export\\s+)?(?:const|let|var)\\s+"+ID+"\\b[^;]*=>");
  private static final Pattern TS_METHOD=Pattern.compile("^(?:(?:public|private|protected|static|async|readonly|override|get|set)\\s+)*"+ID+"\\s*\\(");
  private static final Pattern RUST=Pattern.compile("^(?:pub(?:\\s*\\([^)]{0,64}\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(struct|enum|trait|type|fn|mod)\\s+"+ID);
  private static final Pattern RUST_IMPL=Pattern.compile("^impl(?:\\s*<[^>]{0,256}>)?\\s+"+ID+"(?:\\s+for\\s+"+ID+")?");
  private static final Pattern GO_TYPE=Pattern.compile("^type\\s+"+ID);
  private static final Pattern GO_FN=Pattern.compile("^func\\s+(?:\\(([^)]{1,128})\\)\\s*)?"+ID+"\\s*\\(");
  private static final Pattern PY_CLASS=Pattern.compile("^class\\s+"+ID);
  private static final Pattern PY_FN=Pattern.compile("^(?:async\\s+)?def\\s+"+ID+"\\s*\\(");
  static String language(String path){String name=path.toLowerCase(Locale.ROOT);if(name.endsWith(".java"))return "JAVA";if(name.matches(".*\\.(ts|tsx|mts|cts)$"))return "TYPESCRIPT";if(name.matches(".*\\.(js|jsx|mjs|cjs)$"))return "JAVASCRIPT";if(name.endsWith(".rs"))return "RUST";if(name.endsWith(".go"))return "GO";if(name.endsWith(".py"))return "PYTHON";return "OTHER";}
  static boolean source(String path){return !language(path).equals("OTHER");}
  static String fallbackModule(String path){int marker=path.indexOf("/src/");return marker<0?".":path.substring(0,marker);}
  static boolean test(String path){String name=Path.of(path).getFileName().toString();return path.contains("/test/")||path.startsWith("test/")||path.contains("/tests/")||path.startsWith("tests/")||path.contains("/it/")||path.startsWith("it/")||path.contains("/integration/")||name.matches(".*\\.(test|spec)\\.[cm]?[jt]sx?")||name.endsWith("_test.go")||name.startsWith("test_")&&name.endsWith(".py");}
  static Boundaries boundaries(Path root,List<String> paths,long deadline)throws java.io.IOException {
    var result=new ArrayList<Boundary>();var warnings=new ArrayList<String>();int bytes=0,count=0;
    for(String path:paths){RunCancellation.propagate(null);String name=Path.of(path).getFileName().toString();if(!Set.of("pom.xml","package.json","Cargo.toml","go.mod","pyproject.toml","setup.py").contains(name))continue;
      if(++count>64||System.nanoTime()-deadline>=0){warnings.add("Build boundary inventory limited");break;}
      Path relative=Path.of(path);if(relative.isAbsolute()||relative.normalize().startsWith("..")||RepositoryMapService.sensitive(relative))continue;
      Path file=root.resolve(relative).normalize();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))continue;
      String text="";try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] content=input.readNBytes(16385);bytes+=content.length;if(content.length>16384){warnings.add("Oversized build metadata omitted");continue;}text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();}catch(java.io.IOException error){warnings.add("Unreadable build metadata omitted");continue;}
      String parent=relative.getParent()==null?".":relative.getParent().toString().replace('\\','/');String identity="";
      if(name.equals("go.mod")){var match=Pattern.compile("(?m)^\\s*module\\s+([A-Za-z0-9_./-]{1,128})\\s*(?://[^\\n]*)?$").matcher(text);if(match.find())identity=match.group(1);}
      if(name.equals("Cargo.toml")){boolean packageSection=false;for(String line:text.lines().toList()){String value=line.strip();if(value.startsWith("["))packageSection=value.equals("[package]");if(packageSection){var match=Pattern.compile("^name\\s*=\\s*\"([A-Za-z0-9_-]{1,128})\"").matcher(value);if(match.find()){identity=match.group(1).replace('-','_');break;}}}}
      result.add(new Boundary(parent,identity,name));
    }
    return new Boundaries(List.copyOf(result),bytes,List.copyOf(warnings));
  }
  static Parsed parse(String path,String text,Boundary boundary) {
    String language=language(path);var lexical=lex(text,language);if(!lexical.valid())return new Parsed("","LEXICAL_ERROR",List.of(),List.of());
    String namespace=relative(path,boundary.root());namespace=namespace.substring(0,namespace.lastIndexOf('.')).replace('/','.');
    String pkg=namespace.contains(".")?namespace.substring(0,namespace.lastIndexOf('.')):"";
    if(language.equals("GO")){var match=Pattern.compile("(?m)^\\s*package\\s+"+ID).matcher(lexical.masked());pkg=match.find()?match.group(1):"";}
    var symbols=new ArrayList<RepositoryMapService.Symbol>();String owner=null;int ownerDepth=-1,depth=0,ownerIndent=-1,lineNumber=0;
    for(String line:lexical.masked().split("\\n",-1)) {
      RunCancellation.propagate(null);lineNumber++;String value=line.strip();if(value.isEmpty())continue;int indent=line.length()-line.stripLeading().length();
      if(language.equals("PYTHON")&&owner!=null&&indent<=ownerIndent){owner=null;ownerIndent=-1;}
      if(owner!=null&&!language.equals("PYTHON")&&depth<ownerDepth)owner=null;
      if(language.equals("TYPESCRIPT")||language.equals("JAVASCRIPT")) {
        var type=TS_TYPE.matcher(value);var function=TS_FN.matcher(value);var arrow=TS_ARROW.matcher(value);var method=TS_METHOD.matcher(value);
        if(type.find()){add(symbols,namespace+"."+type.group(2),type.group(1).toUpperCase(Locale.ROOT),lineNumber,false);if(Set.of("class","interface").contains(type.group(1))&&value.contains("{")){owner=type.group(2);ownerDepth=depth+1;}}
        else if(function.find())add(symbols,namespace+"."+function.group(1),"FUNCTION",lineNumber,false);
        else if(arrow.find())add(symbols,namespace+"."+arrow.group(1),"FUNCTION",lineNumber,false);
        else if(owner!=null&&depth==ownerDepth&&method.find()&&!Set.of("if","for","while","switch","catch").contains(method.group(1)))add(symbols,namespace+"."+owner+"."+method.group(1),"METHOD",lineNumber,false);
      } else if(language.equals("RUST")) {
        var implementation=RUST_IMPL.matcher(value);var declaration=RUST.matcher(value);
        if(implementation.find()&&value.contains("{")){owner=implementation.group(2)==null?implementation.group(1):implementation.group(2);ownerDepth=depth+1;}
        if(declaration.find()){String kind=declaration.group(1),name=declaration.group(2);boolean method=kind.equals("fn")&&owner!=null&&depth==ownerDepth;add(symbols,namespace+"."+(method?owner+".":"")+name,kind.equals("fn")?(method?"METHOD":"FUNCTION"):kind.toUpperCase(Locale.ROOT),lineNumber,kind.equals("fn")&&name.equals("main"));}
      } else if(language.equals("GO")) {
        var type=GO_TYPE.matcher(value);var function=GO_FN.matcher(value);
        if(type.find())add(symbols,pkg+"."+type.group(1),"TYPE",lineNumber,false);
        if(function.find()){String receiver=function.group(1),name=function.group(2);if(receiver!=null){String[] words=receiver.replace('*',' ').strip().split("\\s+");receiver=words[words.length-1];}add(symbols,pkg+"."+(receiver==null?"":receiver+".")+name,receiver==null?"FUNCTION":"METHOD",lineNumber,receiver==null&&pkg.equals("main")&&name.equals("main"));}
      } else if(language.equals("PYTHON")) {
        var type=PY_CLASS.matcher(value);var function=PY_FN.matcher(value);
        if(type.find()){owner=type.group(1);ownerIndent=indent;add(symbols,namespace+"."+owner,"CLASS",lineNumber,false);}
        if(function.find())add(symbols,namespace+"."+(owner==null?"":owner+".")+function.group(1),owner==null?"FUNCTION":"METHOD",lineNumber,false);
      }
      if(!language.equals("PYTHON")){for(char ch:line.toCharArray()){if(ch=='{')depth++;else if(ch=='}')depth--;}if(owner!=null&&depth<ownerDepth)owner=null;}
    }
    var imports=imports(lexical.tokens(),language);
    return new Parsed(pkg,symbols.size()>=64||imports.size()>128?"DECLARATION_LIMIT":"HEURISTIC",List.copyOf(symbols),imports.stream().limit(128).toList());
  }
  private static void add(List<RepositoryMapService.Symbol> result,String name,String kind,int line,boolean entry){if(result.size()<64&&name.length()<=2048)result.add(new RepositoryMapService.Symbol(name,kind,line,entry));}
  private static String relative(String path,String module){return module.equals(".")?path:path.substring(module.length()+1);}
  private static List<String> imports(List<Token> tokens,String language) {
    var result=new LinkedHashSet<String>();
    for(int i=0;i<tokens.size()&&result.size()<129;i++) {
      Token token=tokens.get(i);if(token.literal())continue;String value=token.value();
      if(language.equals("TYPESCRIPT")||language.equals("JAVASCRIPT")) {
        if((value.equals("from")||value.equals("import"))&&i+1<tokens.size()&&tokens.get(i+1).literal())result.add(tokens.get(i+1).value());
        if(Set.of("require","import").contains(value)&&i+2<tokens.size()&&tokens.get(i+1).value().equals("(")&&tokens.get(i+2).literal())result.add(tokens.get(i+2).value());
      } else if(language.equals("GO")&&value.equals("import")) {
        if(i+1<tokens.size()&&tokens.get(i+1).value().equals("(")){for(int j=i+2;j<tokens.size()&&!tokens.get(j).value().equals(")");j++)if(tokens.get(j).literal())result.add(tokens.get(j).value());}
        else for(int j=i+1;j<tokens.size()&&j<i+4&&tokens.get(j).line()==token.line();j++)if(tokens.get(j).literal())result.add(tokens.get(j).value());
      } else if(language.equals("RUST")) {
        if(value.equals("mod")&&i+2<tokens.size()&&tokens.get(i+2).value().equals(";"))result.add("mod:"+tokens.get(i+1).value());
        if(value.equals("use")){int end=i+1;while(end<tokens.size()&&!tokens.get(end).value().equals(";")&&end<i+257)end++;expandUse(tokens,i+1,end,"",result,0);}
      } else if(language.equals("PYTHON")) {
        if(value.equals("from")){var name=new StringBuilder();for(int j=i+1;j<tokens.size()&&tokens.get(j).line()==token.line()&&!tokens.get(j).value().equals("import");j++)name.append(tokens.get(j).value());if(!name.isEmpty())result.add(name.toString());}
        if(value.equals("import")&&(i==0||tokens.get(i-1).line()!=token.line())){var name=new StringBuilder();for(int j=i+1;j<tokens.size()&&tokens.get(j).line()==token.line();j++){String part=tokens.get(j).value();if(part.equals(",")){if(!name.isEmpty())result.add(name.toString());name.setLength(0);}else if(part.equals("as")){if(!name.isEmpty())result.add(name.toString());name.setLength(0);j++;}else name.append(part);}if(!name.isEmpty())result.add(name.toString());}
      }
    }
    return result.stream().map(HeuristicSourceIndex::safeImport).filter(value->!value.isBlank()).distinct().toList();
  }
  private static String safeImport(String value){
    if(value.codePoints().anyMatch(Character::isISOControl))return "IMPORT_METADATA_REDACTED";
    String safe=dev.mikoto2000.rei.event.CredentialRedactor.redact(value);
    if(!safe.equals(value))return "IMPORT_METADATA_REDACTED";
    try{var uri=java.net.URI.create(value);if(uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)return "IMPORT_METADATA_REDACTED";}catch(IllegalArgumentException ignored){}
    return safe;
  }
  private static void expandUse(List<Token> tokens,int start,int end,String prefix,Set<String> result,int depth) {
    if(depth>16||result.size()>=129)return;var path=new StringBuilder(prefix);
    for(int i=start;i<end&&result.size()<129;i++){String value=tokens.get(i).value();
      if(value.equals("{")){int close=i+1,nesting=1;while(close<end&&nesting>0){String part=tokens.get(close).value();if(part.equals("{"))nesting++;if(part.equals("}"))nesting--;close++;}expandUse(tokens,i+1,close-1,path.toString(),result,depth+1);path.setLength(0);path.append(prefix);i=close-1;}
      else if(value.equals(",")){if(path.length()>prefix.length())result.add(path.toString());path.setLength(0);path.append(prefix);}
      else if(value.equals("as")){if(path.length()>prefix.length())result.add(path.toString());while(i+1<end&&!Set.of(",","}").contains(tokens.get(i+1).value()))i++;path.setLength(0);path.append(prefix);}
      else path.append(value);
    }
    if(path.length()>prefix.length())result.add(path.toString());
  }
  private static Lexed lex(String text,String language) {
    char[] masked=text.toCharArray();var tokens=new ArrayList<Token>();int line=1;
    for(int i=0;i<text.length();) {
      if((i&511)==0)RunCancellation.propagate(null);if(tokens.size()>32768)return new Lexed("",List.of(),false);
      char ch=text.charAt(i);if(Character.isWhitespace(ch)){if(ch=='\n')line++;i++;continue;}
      boolean slash=i+1<text.length()&&ch=='/'&&text.charAt(i+1)=='/';boolean block=i+1<text.length()&&ch=='/'&&text.charAt(i+1)=='*';boolean python=language.equals("PYTHON")&&ch=='#';
      if(slash||python){while(i<text.length()&&text.charAt(i)!='\n')masked[i++]=' ';continue;}
      if(block){int nesting=1;masked[i++]=' ';masked[i++]=' ';while(i<text.length()&&nesting>0){if(i+1<text.length()&&text.charAt(i)=='/'&&text.charAt(i+1)=='*'&&language.equals("RUST")){if(++nesting>32)return new Lexed("",List.of(),false);masked[i++]=' ';masked[i++]=' ';}else if(i+1<text.length()&&text.charAt(i)=='*'&&text.charAt(i+1)=='/'){nesting--;masked[i++]=' ';masked[i++]=' ';}else {if(text.charAt(i)=='\n')line++;else masked[i]=' ';i++;}}if(nesting!=0)return new Lexed("",List.of(),false);continue;}
      if(ch=='\''&&language.equals("RUST")&&i+2<text.length()&&Character.isJavaIdentifierStart(text.charAt(i+1))&&text.charAt(i+2)!='\''){tokens.add(new Token("'",false,line));i++;continue;}
      if(ch=='\''||ch=='"'||ch=='`') {
        int startLine=line;boolean triple=language.equals("PYTHON")&&i+2<text.length()&&text.charAt(i+1)==ch&&text.charAt(i+2)==ch;int delimiter=triple?3:1;for(int j=0;j<delimiter;j++)masked[i++]=' ';boolean closed=false;StringBuilder literal=new StringBuilder();
        while(i<text.length()){char current=text.charAt(i);boolean end=current==ch&&(!triple||i+2<text.length()&&text.charAt(i+1)==ch&&text.charAt(i+2)==ch);if(end){for(int j=0;j<delimiter;j++)masked[i++]=' ';closed=true;break;}if(current=='\n'){line++;if(!triple&&ch!='`'&&!language.equals("GO"))return new Lexed("",List.of(),false);}if(current=='\\'&&!(language.equals("GO")&&ch=='`')){masked[i++]=' ';if(i>=text.length())break;current=text.charAt(i);}if(current!='\n')masked[i]=' ';if(literal.length()<2049)literal.append(current);i++;}
        if(!closed)return new Lexed("",List.of(),false);tokens.add(new Token(literal.length()>2048?"":literal.toString(),true,startLine));continue;
      }
      if(Character.isJavaIdentifierStart(ch)){int begin=i++;while(i<text.length()&&Character.isJavaIdentifierPart(text.charAt(i)))i++;tokens.add(new Token(text.substring(begin,i),false,line));}
      else {tokens.add(new Token(String.valueOf(ch),false,line));i++;}
    }
    return new Lexed(new String(masked),List.copyOf(tokens),true);
  }
  static List<RepositoryMapService.Relation> relations(List<RepositoryMapService.File> files,Boundaries boundaries) {
    var result=new LinkedHashSet<RepositoryMapService.Relation>();var byPath=new HashMap<String,RepositoryMapService.File>();files.forEach(file->byPath.put(file.path(),file));
    for(var file:files){RunCancellation.propagate(null);if(!file.analysisMode().equals("HEURISTIC"))continue;
      for(String imported:file.imports()) {
        var targets=new LinkedHashSet<String>();String language=file.language();
        if(Set.of("TYPESCRIPT","JAVASCRIPT").contains(language)&&imported.startsWith(".")){String base=normalize(Path.of(file.path()).getParent(),imported);if(base!=null){var options=new LinkedHashSet<String>();options.add(base);String stem=base.replaceFirst("\\.[cm]?[jt]sx?$","");for(String extension:List.of(".ts",".tsx",".mts",".cts",".js",".jsx",".mjs",".cjs")){options.add(stem+extension);options.add(base+"/index"+extension);}unique(options,byPath,targets);}}
        if(language.equals("RUST"))rustTargets(file,imported,byPath,boundaries,targets);
        if(language.equals("GO"))for(var boundary:boundaries.values())if(boundary.kind().equals("go.mod")&&!boundary.name().isEmpty()&&(imported.equals(boundary.name())||imported.startsWith(boundary.name()+"/"))){String folder=join(boundary.root(),imported.equals(boundary.name())?"":imported.substring(boundary.name().length()+1));for(var candidate:files)if(candidate.language().equals("GO")&&!test(candidate.path())&&Objects.equals(parent(candidate.path()),folder))targets.add(candidate.path());}
        if(language.equals("PYTHON")){String base;if(imported.startsWith(".")){int level=0;while(level<imported.length()&&imported.charAt(level)=='.')level++;Path folder=Path.of(file.path()).getParent();for(int j=1;j<level&&folder!=null;j++)folder=folder.getParent();base=normalize(folder,imported.substring(level).replace('.','/'));}else base=join(file.module(),imported.replace('.','/'));if(base!=null){var options=new LinkedHashSet<String>(List.of(base+".py",base+"/__init__.py"));if(!imported.startsWith("."))options.add(join(file.module(),"src/"+imported.replace('.','/'))+".py");options.removeIf(path->!file.module().equals(".")&&!path.startsWith(file.module()+"/"));unique(options,byPath,targets);}}
        for(String target:targets)if(!target.equals(file.path()))result.add(new RepositoryMapService.Relation(file.path(),target,"HEURISTIC_IMPORT"));
      }
      if(test(file.path())){String base=Path.of(file.path()).getFileName().toString().replaceFirst("\\.(test|spec)(\\.[cm]?[jt]sx?)$","$2").replaceFirst("_test\\.go$",".go").replaceFirst("^test_","");var matches=files.stream().filter(candidate->!test(candidate.path())&&candidate.module().equals(file.module())&&Path.of(candidate.path()).getFileName().toString().equals(base)).toList();if(matches.size()==1)result.add(new RepositoryMapService.Relation(file.path(),matches.getFirst().path(),"HEURISTIC_TEST_NAME_CANDIDATE"));}
    }
    return List.copyOf(result);
  }
  private static void rustTargets(RepositoryMapService.File file,String imported,Map<String,RepositoryMapService.File> byPath,Boundaries boundaries,Set<String> targets) {
    String base=null;String relative=relative(file.path(),file.module());String rustModule=relative.replaceFirst("^src/","").replaceFirst("\\.rs$","").replaceFirst("(?:^|/)(lib|main|mod)$","");
    if(imported.startsWith("mod:")){String folder=Set.of("lib.rs","main.rs","mod.rs").contains(Path.of(file.path()).getFileName().toString())?parent(file.path()):file.path().replaceFirst("\\.rs$","");base=join(folder,imported.substring(4));}
    else {String[] parts=imported.split("::");var boundary=boundaries.forPath(file.path());int start=0;String module=rustModule;
      if(parts.length>0&&(parts[0].equals("crate")||!boundary.name().isEmpty()&&parts[0].equals(boundary.name()))){module="";start=1;}
      else if(parts.length>0&&parts[0].equals("self")){start=1;}
      else if(parts.length>0&&parts[0].equals("super")){while(start<parts.length&&parts[start].equals("super")){int slash=module.lastIndexOf('/');module=slash<0?"":module.substring(0,slash);start++;}}
      else return;
      String suffix=String.join("/",Arrays.copyOfRange(parts,start,parts.length));base=join(join(file.module(),"src"),join(module,suffix));
    }
    for(int attempt=0;base!=null&&attempt<16;attempt++){var options=new LinkedHashSet<String>(List.of(base+".rs",base+"/mod.rs"));unique(options,byPath,targets);if(!targets.isEmpty())return;int slash=base.lastIndexOf('/');if(slash<0||base.equals(join(file.module(),"src")))break;base=base.substring(0,slash);}
  }
  private static void unique(Set<String> options,Map<String,RepositoryMapService.File> files,Set<String> result){var matches=options.stream().filter(files::containsKey).filter(path->files.get(path).analysisMode().equals("HEURISTIC")).toList();if(matches.size()==1)result.add(matches.getFirst());}
  private static String normalize(Path parent,String imported){if(parent==null)parent=Path.of("");try{Path path=parent.resolve(imported).normalize();if(path.isAbsolute()||path.startsWith(".."))return null;return path.toString().replace('\\','/');}catch(InvalidPathException error){return null;}}
  private static String parent(String path){Path parent=Path.of(path).getParent();return parent==null?".":parent.toString().replace('\\','/');}
  private static String join(String base,String suffix){if(base==null||base.isEmpty()||base.equals("."))return suffix.isEmpty()?".":suffix;if(suffix.isEmpty()||suffix.equals("."))return base;return base+"/"+suffix;}
}
