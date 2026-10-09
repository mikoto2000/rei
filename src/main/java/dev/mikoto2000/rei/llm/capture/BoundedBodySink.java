package dev.mikoto2000.rei.llm.capture;

import java.io.*;
import java.lang.reflect.*;
import okio.*;
/** Buffering itself is bounded, including a producer's single oversized write. */
final class BoundedBodySink implements InvocationHandler {
  static final class TooLarge extends IOException {}
  private final Buffer buffer=new Buffer();
  private BufferedSink proxy;
  BoundedBodySink(){proxy=(BufferedSink)Proxy.newProxyInstance(BufferedSink.class.getClassLoader(),new Class<?>[]{BufferedSink.class},this);}
  BufferedSink sink(){return proxy;}
  byte[] bytes(){return buffer.readByteArray();}
  void clear(){buffer.clear();}
  private void capacity(long count) throws IOException {if(count<0||count>CaptureStore.BODY_LIMIT-buffer.size())throw new TooLarge();}
  public Object invoke(Object ignored,Method method,Object[] args) throws Throwable {
    String name=method.getName();
    if(name.equals("outputStream"))return new OutputStream(){
      public void write(int value) throws IOException{capacity(1);buffer.writeByte(value);}
      public void write(byte[] bytes,int offset,int length) throws IOException{capacity(length);buffer.write(bytes,offset,length);}
    };
    if(name.equals("timeout"))return Timeout.NONE;
    if(name.equals("flush")||name.equals("close"))return null;
    if(name.equals("isOpen"))return true;
    if(name.equals("toString"))return "BoundedBodySink";
    if(name.equals("hashCode"))return System.identityHashCode(proxy);
    if(name.equals("equals"))return proxy==args[0];
    if(name.equals("write")&&args[0] instanceof byte[] bytes){capacity(args.length==1?bytes.length:(Integer)args[2]);}
    else if(name.equals("write")&&args[0] instanceof Buffer){capacity((Long)args[1]);}
    else if(name.equals("write")&&args[0] instanceof ByteString bytes){capacity(bytes.size());}
    else if(name.equals("write")&&args[0] instanceof java.nio.ByteBuffer bytes){capacity(bytes.remaining());}
    else if(name.equals("writeUtf8")&&args.length==1){String value=(String)args[0];long count=0;
      for(int i=0;i<value.length();i++){char c=value.charAt(i);count+=c<128?1:c<2048?2:Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))?4:Character.isSurrogate(c)?1:3;if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))i++;capacity(count);}}
    else if(name.equals("writeByte"))capacity(1);
    else throw new UnsupportedOperationException("Unsupported capture sink operation");
    try {var result=method.invoke(buffer,args);return result==buffer?proxy:result;}catch(InvocationTargetException error){throw error.getCause();}
  }
}
