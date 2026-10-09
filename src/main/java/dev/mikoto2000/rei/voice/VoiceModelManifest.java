package dev.mikoto2000.rei.voice;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Curated complete runtime/model set. No arbitrary ONNX file or URL is accepted by the CLI. */
public record VoiceModelManifest(String id,List<Asset> assets) {
  public record Asset(String path,URI url,long bytes,String sha256,String license) {
    public Asset {
      if(path==null||!path.matches("[A-Za-z0-9._/-]+")||path.startsWith("/")
          ||Arrays.asList(path.split("/",-1)).stream().anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))
        throw new IllegalArgumentException("Unsafe voice asset path");
      requireHttps(url);
      if(bytes<1||bytes>512L*1024*1024||sha256==null||!sha256.matches("[0-9a-f]{64}")||license==null||license.isBlank())
        throw new IllegalArgumentException("Invalid fixed voice asset metadata");
    }
  }
  public VoiceModelManifest {
    if(id==null||!id.matches("[A-Za-z0-9_-]{1,120}")||assets==null||assets.isEmpty()||assets.size()>20)
      throw new IllegalArgumentException("Invalid voice manifest");
    assets=List.copyOf(assets);
    if(assets.stream().map(Asset::path).distinct().count()!=assets.size())throw new IllegalArgumentException("Duplicate voice asset");
  }
  public static void requireHttps(URI url) {
    if(url==null||!"https".equalsIgnoreCase(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null)
      throw new IllegalArgumentException("Voice downloads require HTTPS without credentials");
  }
  public long totalBytes(){return assets.stream().mapToLong(Asset::bytes).sum();}
  public static Path assetPath(Path root,String relative) throws IOException {
    Path base=root.toAbsolutePath().normalize();Path path=base.resolve(relative).normalize();
    if(!path.startsWith(base)||path.equals(base))throw new IOException("Voice asset escaped bundle");
    for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked voice asset path refused");
    return path;
  }
  public void verify(Path root) throws IOException {
    for(var asset:assets)verifyAsset(asset,assetPath(root,asset.path()));
  }
  public static void verifyAsset(Asset asset,Path path) throws IOException {
    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=asset.bytes())
      throw new IOException("Missing or invalid voice asset: "+asset.path());
    try(var input=Files.newInputStream(path)) {
      var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int count;
      while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);
      if(!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256()))throw new IOException("Voice SHA-256 mismatch: "+asset.path());
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  public static VoiceModelManifest pinned() {
    String revision="bb53ee204431c90d314c1cc08d28d23e5b7927cc";
    var assets=new ArrayList<Asset>();
    for(var asset:SherpaBackendFactory.ASSETS) {
      String url;
      if(asset.path().equals("jvm.jar"))url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-jvm-1.13.8.jar";
      else if(asset.path().equals("native.jar"))url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-native-lib-win-x64-1.13.8.jar";
      else if(asset.path().contains("silero"))url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";
      else url="https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/"+revision+"/"+Path.of(asset.path()).getFileName();
      assets.add(new Asset(asset.path(),URI.create(url),asset.bytes(),asset.sha256(),asset.path().endsWith("jar")?"Apache-2.0 (sherpa-onnx); MIT (ONNX Runtime)":asset.path().contains("base-")?"MIT (Whisper upstream; fixed converted distribution)":"MIT (Silero VAD)"));
    }
    return new VoiceModelManifest("sherpa-1_13_8-whisper-base-bb53ee20-silero-9e2449e1",assets);
  }
}