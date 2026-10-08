package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Source selection over the existing Javac AST, using request-local snapshot hashes. */
final class JavaSymbolReads {
  public record Candidate(String path,String version,RepositoryMapService.Symbol symbol){}
  static Tools.ReadFileResult read(Tools.ReadFileRequest request,Path root,FileSnapshots snapshots,RepositoryMapService maps,TextReadBudget budget,int lineLimit,int tokens)throws IOException {
    RunCancellation.propagate(null);long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
    if(request.symbol().isBlank()||request.symbol().length()>2048)throw new IllegalArgumentException("symbol must be 1 to 2048 characters");
    if(request.startLine()!=null||request.endLine()!=null||request.offset()!=null||request.charset()!=null)throw new IllegalArgumentException("Symbol selection cannot be mixed with line ranges/offset/charset");
    int context=request.contextLines()==null?0:request.contextLines();if(context<0||context>25)throw new IllegalArgumentException("contextLines must be 0 to 25");
    var paths=request.path()==null||request.path().isBlank()?maps.javaInventory(root,snapshots):List.of(request.path());
    var found=new ArrayList<Candidate>();var failures=new ArrayList<String>();var metadata=new HashMap<String,RepositoryMapService.File>();boolean candidatesTruncated=false;
    String query=request.symbol().replaceAll("\\s+","");
    for(var path:paths){
      RunCancellation.propagate(null);
      if(System.nanoTime()-deadline>=0)throw new IOException("Symbol index time budget exhausted; narrow path");
      if(!path.endsWith(".java"))throw new IOException("Symbol reads require a Java source path");
      var snapshot=snapshots.get(root,Path.of(path));
      if(request.expectedVersion()!=null && !request.expectedVersion().equals(snapshot.version()))throw new IOException("File version mismatch; resolve symbol again");
      var file=maps.describeSnapshot(root,snapshot);metadata.put(file.path(),file);
      if(System.nanoTime()-deadline>=0)throw new IOException("Symbol index time budget exhausted; narrow path");
      if(!file.status().equals("PARSED")){if(failures.size()<8)failures.add(file.path()+": "+file.status());continue;}
      for(var symbol:file.symbols())if(matches(symbol,query)){
        if(found.size()<20)found.add(new Candidate(file.path(),snapshot.version(),symbol));else candidatesTruncated=true;
      }
    }
    found.sort(Comparator.comparing(Candidate::path).thenComparing(candidate->candidate.symbol().symbolId()));
    if(found.size()!=1 || candidatesTruncated || !failures.isEmpty()){
      String error=found.size()>1||candidatesTruncated?"Ambiguous symbol; choose exact symbolId and path":!failures.isEmpty()?"Symbol index incomplete: "+String.join("; ",failures):"Symbol not found";
      var result=new Tools.ReadFileResult(request.path(),null,null,List.of(),candidatesTruncated,error,null,null,0,null,List.copyOf(found),List.of());
      return FileResultBudget.fit(result,Math.max(256,budget.remaining()),tokens,value->{
        if(value.candidates().isEmpty())return value;return new Tools.ReadFileResult(value.path(),null,null,List.of(),true,value.error(),null,null,0,null,value.candidates().subList(0,value.candidates().size()-1),List.of());
      });
    }
    var candidate=found.getFirst();var symbol=candidate.symbol();var snapshot=snapshots.get(root,Path.of(candidate.path()));
    String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(snapshot.bytes())).toString();
    int from=Boolean.FALSE.equals(request.includeJavadoc())?symbol.startOffset():symbol.javadocStartOffset();
    int to=Boolean.FALSE.equals(request.includeBody())?symbol.signatureEndOffset():symbol.endOffset();
    if(from<0||to<from||to>source.length())throw new IOException("AST source positions unavailable");
    if(context>0){int cursor=from;while(cursor>0&&source.charAt(cursor-1)!='\n')cursor--;for(int i=0;i<context&&cursor>0;i++){cursor--;while(cursor>0&&source.charAt(cursor-1)!='\n')cursor--;}from=cursor;
      cursor=to;while(cursor<source.length()&&source.charAt(cursor)!='\n')cursor++;for(int i=0;i<context&&cursor<source.length();i++){cursor++;while(cursor<source.length()&&source.charAt(cursor)!='\n')cursor++;}to=cursor;
    }
    int offset=request.symbolOffset()==null?0:request.symbolOffset();
    if(offset<0||offset>to-from||from+offset<source.length()&&Character.isLowSurrogate(source.charAt(from+offset)))throw new IllegalArgumentException("Invalid symbolOffset");
    int start=from+offset,stop=to,lines=0;
    for(int i=start;i<stop;i++)if(source.charAt(i)=='\n'&&++lines>=Math.min(1000,lineLimit)){stop=i+1;break;}
    if(lineLimit<=0)stop=start;
    var overview=Boolean.TRUE.equals(request.includeOwnerOverview())?metadata.get(candidate.path()).symbols().stream().filter(value->value.name().equals(symbol.owner())&&RepositoryMapService.typeKind(value.kind())).map(value->value.signature().substring(0,Math.min(256,value.signature().length()))).limit(4).toList():List.<String>of();
    if(stop==start && start<to)throw new IOException("Symbol line budget exhausted; narrow the request");
    int available=Math.max(256,budget.remaining());Tools.ReadFileResult result;
    while(true){
      var excerpt=source.substring(start,stop);boolean truncated=stop<to;
      var next=truncated?new Tools.ReadFileRequest(candidate.path(),null,null,candidate.version(),null,null,request.symbol(),request.includeBody(),request.includeJavadoc(),context,request.includeOwnerOverview(),stop-from):null;
      int first=line(source,start),last=line(source,Math.max(start,stop-1));
      result=new Tools.ReadFileResult(candidate.path(),first,last,excerpt.lines().toList(),truncated,null,candidate.version(),next,excerpt.getBytes(StandardCharsets.UTF_8).length,symbol,List.of(),overview);
      if(FileResultBudget.fits(result,available,tokens))break;
      if(stop==start)throw new IOException("Symbol metadata exceeds result budget; narrow request");
      stop=start+(stop-start)/2;if(stop>start&&Character.isLowSurrogate(source.charAt(stop)))stop--;
    }
    budget.reserve(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(result).length);return result;
  }
  private static boolean matches(RepositoryMapService.Symbol symbol,String query){String id=symbol.symbolId();return id.equals(query)||symbol.name().equals(query)||!query.contains("(")&&id.startsWith(query+"(");}
  private static int line(String text,int end){int line=1;for(int i=0;i<end;i++)if(text.charAt(i)=='\n'||text.charAt(i)=='\r'&&(i+1>=text.length()||text.charAt(i+1)!='\n'))line++;return line;}
}
