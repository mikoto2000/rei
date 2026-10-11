package dev.mikoto2000.rei.cli;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.*;

/** HTTP/SSE only; no backend data files or provider credentials are accessed. */
public final class BackendClient implements AutoCloseable {
  public static final ObjectMapper JSON=new ObjectMapper();
  private final HttpClient http;
  private final URI endpoint;
  private final String key;
  private final Set<InputStream> streams=new HashSet<>();
  private long subscriptionGeneration;
  public static final class ApiException extends IOException {
    private final int status;
    ApiException(int status){this(status,"Backend rejected request (HTTP "+status+")");}
    ApiException(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
  }
  public static final class UncertainAcceptanceException extends IOException {
    public final String receiptKey;
    UncertainAcceptanceException(String receiptKey){super("Acceptance could not be confirmed. Inspect receipt "+receiptKey+"; do not resubmit automatically.");this.receiptKey=receiptKey;}
  }
  public BackendClient(URI endpoint,String key) {
    new dev.mikoto2000.rei.launcher.BackendEndpoint(1,UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,endpoint.toString(),1,"READY");
    if(key==null||key.isBlank()||key.indexOf('\r')>=0||key.indexOf('\n')>=0)throw new IllegalArgumentException("REI_API_KEY is required");
    this.endpoint=endpoint;this.key=key;
    http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3))
        .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException error){}}).build();
  }
  private HttpRequest.Builder request(String path) {
    if(!path.startsWith("/api/v1/")||path.contains("#"))throw new IllegalArgumentException("Invalid API path");
    return HttpRequest.newBuilder(endpoint.resolve(path)).header("Authorization","Bearer "+key).header("Accept","application/json");
  }
  private JsonNode response(HttpRequest request)throws IOException,InterruptedException {
    var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
    try(var body=response.body()) {
      if(response.statusCode()==409) {
        byte[] bytes=body.readNBytes(16385);
        if(bytes.length>0&&bytes.length<=16384)try {
          var conflict=JSON.readTree(bytes);
          String project=conflict.path("projectId").asText(),session=conflict.path("sessionId").asText(),run=conflict.path("runId").asText();
          if(safeIdentity(project)&&safeIdentity(session)&&safeIdentity(run))throw new ApiException(409,"Session busy (HTTP 409): Project "+project+", Session "+session+", Run "+run+". Select another Session or explicitly send /chat --run "+run+" MESSAGE.");
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){/* Never expose arbitrary error bodies. */}
        throw new ApiException(409);
      }
      if(response.statusCode()<200||response.statusCode()>=300)throw new ApiException(response.statusCode());
      byte[] bytes=body.readNBytes(1048577);if(bytes.length>1048576)throw new IOException("Backend response capacity exceeded");
      return bytes.length==0?JSON.nullNode():JSON.readTree(bytes);
    }
  }
  private static boolean safeIdentity(String value){return value.matches("[A-Za-z0-9:_-]{1,256}");}
  public JsonNode get(String path)throws IOException,InterruptedException{return response(request(path).timeout(Duration.ofSeconds(10)).GET().build());}
  public JsonNode post(String path,Object body)throws IOException,InterruptedException {
    return mutation("POST",path,body);
  }
  public JsonNode mutation(String method,String path,Object body)throws IOException,InterruptedException {
    if(!Set.of("POST","PATCH","DELETE").contains(method))throw new IllegalArgumentException("Invalid API mutation");
    return response(request(path).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build());
  }
  public JsonNode submit(String project,String session,String message,String receiptKey)throws IOException,InterruptedException {
    return submit(project,session,message,receiptKey,"EXCLUSIVE");
  }
  public JsonNode submit(String project,String session,String message,String receiptKey,String mode)throws IOException,InterruptedException {
    if(!receiptKey.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid receipt key");
    if(!Set.of("EXCLUSIVE","READ_ONLY","CONVERSATION").contains(mode))throw new IllegalArgumentException("Invalid conversation mode");
    var body=new LinkedHashMap<String,Object>();body.put("projectId",project);body.put("sessionId",session);body.put("message",message);body.put("mode",mode);
    try {
      return response(request("/api/v1/chat").timeout(Duration.ofSeconds(30)).header("Idempotency-Key",receiptKey).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build());
    } catch(ApiException rejected){
      if(rejected.status()<500 && rejected.status()!=408)throw rejected;
      return recoverReceipt(receiptKey);
    }
    catch(IOException lost) {
      return recoverReceipt(receiptKey);
    }
  }
  private JsonNode recoverReceipt(String receiptKey)throws IOException,InterruptedException {
    try{return get("/api/v1/chat/receipts/"+receiptKey);}
    catch(IOException unresolved){throw new UncertainAcceptanceException(receiptKey);}
  }
  public long events(String run,long after,Consumer<SseDecoder.Event> consumer)throws IOException,InterruptedException {
    long generation;synchronized(streams){generation=subscriptionGeneration;}
    var request=request("/api/v1/runs/"+segment(run)+"/events").header("Last-Event-ID",Long.toString(after)).GET().build();
    var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
    try(var body=response.body()) {
      synchronized(streams){if(generation!=subscriptionGeneration)return after;streams.add(body);}
      if(response.statusCode()!=200)throw new ApiException(response.statusCode());
      var decoder=new SseDecoder(after);decoder.read(new InputStreamReader(body,StandardCharsets.UTF_8),consumer);return decoder.sequence();
    } finally{synchronized(streams){streams.remove(response.body());}}
  }
  /** Stops this client's subscription; never sends cancellation. */
  public void detach(){List<InputStream> current;synchronized(streams){subscriptionGeneration++;current=List.copyOf(streams);streams.clear();}for(var stream:current)try{stream.close();}catch(IOException ignored){}}
  public String redact(String text){return text.replace(key,"[redacted]");}
  public static String segment(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
  @Override public void close(){detach();http.close();}
}
