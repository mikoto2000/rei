package dev.mikoto2000.rei.externalagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Bounded untrusted text replacements; only parent-selected snapshot files can be changed. */
public record ImplementationProposal(List<Edit> edits) {
  public record Edit(String path,String expectedSha256,String replacement) {}
  public ImplementationProposal { edits=List.copyOf(edits); }
  public static ImplementationProposal parse(JsonNode node) {
    if(node==null || !node.isObject() || node.size()!=1 || !node.path("edits").isArray() || node.path("edits").isEmpty() || node.path("edits").size()>32)
      throw new IllegalArgumentException("Require 1 to 32 complete text edits");
    var edits=new ArrayList<Edit>();
    for(var value:node.path("edits")) {
      if(!value.isObject() || value.size()!=3 || !value.path("path").isTextual() || !value.path("expectedSha256").isTextual() || !value.path("replacement").isTextual())throw new IllegalArgumentException("Invalid implementation edit");
      edits.add(new Edit(value.path("path").textValue(),value.path("expectedSha256").textValue(),value.path("replacement").textValue()));
    }
    return new ImplementationProposal(edits);
  }
  public List<String> apply(Path directory,Map<String,String> manifest)throws IOException {
    Path root=directory.toRealPath();var paths=new LinkedHashMap<Path,byte[]>();int total=0;
    if(edits.isEmpty() || edits.size()>32)throw new IllegalArgumentException("Require 1 to 32 edits");
    for(var edit:edits) {
      String name=edit.path();
      if(name==null || name.isBlank() || name.length()>1024 || name.contains("\\") || name.contains(":") || name.startsWith("/") || Arrays.stream(name.split("/",-1)).anyMatch(p->p.isBlank() || p.equals(".") || p.equals("..") || p.equalsIgnoreCase(".git")) || !manifest.containsKey(name))throw new IllegalArgumentException("Edit is outside the selected source snapshot");
      if(name.codePoints().anyMatch(Character::isISOControl) || ExternalAgentSourceSnapshot.excluded(Path.of(name)) || Arrays.stream(name.split("/")).anyMatch(p->Set.of(".gitattributes",".gitmodules",".gitignore").contains(p.toLowerCase(Locale.ROOT))))throw new IllegalArgumentException("Repository execution or excluded configuration cannot be edited by implementation proposals");
      Path path=root.resolve(name);
      if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root) || !path.toRealPath().equals(path))throw new IllegalArgumentException("Edit path is not a regular snapshot file");
      if(edit.expectedSha256()==null || !edit.expectedSha256().matches("[0-9a-f]{64}") || !edit.expectedSha256().equals(manifest.get(name)) || Files.size(path)>65536 || !edit.expectedSha256().equals(sha256(Files.readAllBytes(path))))throw new IllegalArgumentException("Source snapshot is stale");
      if(edit.replacement()==null || edit.replacement().indexOf(0)>=0)throw new IllegalArgumentException("Binary replacement rejected");
      byte[] bytes=edit.replacement().getBytes(StandardCharsets.UTF_8);total+=bytes.length;
      if(!edit.replacement().equals(new String(bytes,StandardCharsets.UTF_8)))throw new IllegalArgumentException("Malformed text replacement");
      if(bytes.length>65536 || total>262144 || paths.putIfAbsent(path,bytes)!=null)throw new IllegalArgumentException("Duplicate or oversized edit");
    }
    // The worktree is disposable. A write failure remains FAILED and can never authorize a merge.
    for(var entry:paths.entrySet())Files.write(entry.getKey(),entry.getValue(),StandardOpenOption.TRUNCATE_EXISTING);
    return edits.stream().map(Edit::path).toList();
  }
  public static String sha256(byte[] content) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}
    catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
