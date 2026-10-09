package dev.mikoto2000.rei.llm.capture;

import java.io.IOException;
import okhttp3.*;
import okio.*;

/** Runs after known rewrites. Captures repeatable, known-size bodies with a hard bounded sink. */
public final class CaptureInterceptor implements Interceptor {
  private final CaptureStore store;
  private final CaptureStore.LogicalCall logical;
  public CaptureInterceptor(CaptureStore store,CaptureStore.LogicalCall logical){this.store=store;this.logical=logical;}
  @Override public Response intercept(Chain chain) throws IOException {
    var request=chain.request();
    if(logical==null || !store.enabled(logical) || !request.url().encodedPath().endsWith("/chat/completions"))return chain.proceed(request);
    boolean copying=store.tryCopy();
    CaptureStore.AttemptInfo attempt=null;
    try{
      var body=request.body();byte[] bytes=null;String state=null;
      if(!copying)state="CAPTURE_FAILED";
      else if(!store.canCopy(logical))state="SKIPPED_TOO_LARGE";
      else if(body==null||body.isDuplex())state="SKIPPED_UNSUPPORTED";
      else if(body.isOneShot())state="SKIPPED_ONE_SHOT";
      else if(body.contentLength()<0)state="SKIPPED_UNSUPPORTED";
      else if(body.contentLength()>CaptureStore.BODY_LIMIT)state="SKIPPED_TOO_LARGE";
      else {
        var bounded=new BoundedBodySink();
        try{body.writeTo(bounded.sink());bytes=bounded.bytes();}
        catch(BoundedBodySink.TooLarge tooLarge){bounded.clear();state="SKIPPED_TOO_LARGE";}
        catch(IOException|RuntimeException failure){bounded.clear();state="CAPTURE_FAILED";}
      }
      attempt=store.prepare(logical,bytes,body==null||body.contentType()==null?null:body.contentType().toString(),state);
    }catch(RuntimeException|IOException ignored){
      try{attempt=store.prepare(logical,null,null,"CAPTURE_FAILED");}catch(RuntimeException ignoredAgain){}
    }
    finally{if(copying)store.releaseCopy();}
    var id=attempt==null?null:attempt.attemptId();
    update(id,"SEND_STARTED",null);
    try{
      var response=chain.proceed(request);update(id,"HTTP_RESPONSE",response.code());return response;
    }catch(IOException|RuntimeException error){update(id,chain.call().isCanceled()?"CANCELLED":"SEND_FAILED",null);throw error;}
  }
  private void update(String id,String state,Integer status){try{store.outcome(id,state,status);}catch(RuntimeException ignored){}}

}
