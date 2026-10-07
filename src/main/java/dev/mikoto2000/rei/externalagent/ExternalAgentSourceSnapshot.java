package dev.mikoto2000.rei.externalagent;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Provider-independent bounded source data and exact hash evidence. */
final class ExternalAgentSourceSnapshot {
  private ExternalAgentSourceSnapshot() {}
  private static void check(BooleanSupplier cancelled,long deadline)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();if(System.nanoTime()>=deadline)throw new IOException("Snapshot deadline reached");}
  static boolean excluded(Path relative){for(var part:relative){String name=part.toString().toLowerCase(Locale.ROOT);if(Set.of(".git",".claude",".codex",".agents",".aws",".m2","target","build","node_modules").contains(name)||name.matches("\\.env(?:\\..*)?|credentials(?:[._].*)?|secrets(?:[._].*)?|.*\\.(key|pem)"))return true;}return false;}
  static byte[] read(Path file)throws IOException{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)throw new IOException();try(var stream=Files.newInputStream(file)){var bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IOException();return bytes;}}
  static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception error){throw new IllegalStateException(error);}}
  static List<Map<String,Object>> snapshot(Path root,Path target,BooleanSupplier cancelled,long deadline)throws IOException{
    var files=new ArrayList<Map<String,Object>>();var visited=new int[]{0};var total=new int[]{0};Path selected=target==null?root:target;if(excluded(root.relativize(selected)))throw new IOException();
    Files.walkFileTree(selected,EnumSet.noneOf(FileVisitOption.class),16,new SimpleFileVisitor<>(){
      private void visit(Path path)throws IOException{check(cancelled,deadline);if(++visited[0]>512||!path.toRealPath().startsWith(root))throw new IOException();}
      @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs)throws IOException{visit(directory);return excluded(root.relativize(directory))?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;}
      @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{visit(file);if(excluded(root.relativize(file)))return FileVisitResult.CONTINUE;if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException();var bytes=read(file);total[0]+=bytes.length;if(files.size()>=32||total[0]>262144)throw new IOException();String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException invalid){throw new IOException();}if(text.indexOf('\0')>=0)throw new IOException();files.add(Map.of("path",root.relativize(file).toString(),"sha256",hash(bytes),"text",dev.mikoto2000.rei.event.CredentialRedactor.redact(text)));return FileVisitResult.CONTINUE;}
    });if(files.isEmpty())throw new IOException();files.sort(Comparator.comparing(file->(String)file.get("path")));return List.copyOf(files);
  }
}
