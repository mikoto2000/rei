package dev.mikoto2000.rei;

import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Test-only real ConPTY host. Output is drained independently through final teardown. */
final class WindowsPty implements AutoCloseable {
  interface Console extends StdCallLibrary {
    Console API=Native.load("kernel32",Console.class);
    int CreatePseudoConsole(Coord size,HANDLE input,HANDLE output,int flags,HANDLEByReference console);
    void ClosePseudoConsole(HANDLE console);
    boolean InitializeProcThreadAttributeList(Pointer list,int count,int flags,Pointer size);
    boolean UpdateProcThreadAttribute(Pointer list,int flags,Pointer attribute,Pointer value,BaseTSD.SIZE_T size,Pointer previous,Pointer returned);
    void DeleteProcThreadAttributeList(Pointer list);
    boolean CreateProcessW(WString application,char[] command,Pointer processSecurity,Pointer threadSecurity,boolean inherit,int flags,Pointer environment,WString directory,Startup startup,WinBase.PROCESS_INFORMATION process);
  }
  @Structure.FieldOrder({"x","y"}) public static class Coord extends Structure implements Structure.ByValue { public short x=120,y=40; }
  @Structure.FieldOrder({"base","attributes"}) public static class Startup extends Structure { public WinBase.STARTUPINFO base=new WinBase.STARTUPINFO();public Pointer attributes; }
  private HANDLE input,output,console,process;
  private Memory attributes;
  private final ByteArrayOutputStreamBuffer transcript=new ByteArrayOutputStreamBuffer();
  private Thread drain;
  private boolean closed;
  WindowsPty(List<String> argv,Path directory)throws IOException {
    HANDLE inputRead=null,outputWrite=null;
    try {
      var read=new HANDLEByReference();var write=new HANDLEByReference();
      require(Kernel32.INSTANCE.CreatePipe(read,write,null,0));inputRead=read.getValue();input=write.getValue();
      require(Kernel32.INSTANCE.CreatePipe(read,write,null,0));output=read.getValue();outputWrite=write.getValue();
      var reference=new HANDLEByReference();int status=Console.API.CreatePseudoConsole(new Coord(),inputRead,outputWrite,0,reference);
      if(status!=0)throw new IOException("CreatePseudoConsole HRESULT "+Integer.toHexString(status));console=reference.getValue();
      var size=new Memory(Native.SIZE_T_SIZE);Console.API.InitializeProcThreadAttributeList(null,1,0,size);
      long required=Native.SIZE_T_SIZE==8?size.getLong(0):Integer.toUnsignedLong(size.getInt(0));attributes=new Memory(required);
      require(Console.API.InitializeProcThreadAttributeList(attributes,1,0,size));
      require(Console.API.UpdateProcThreadAttribute(attributes,0,Pointer.createConstant(0x20016),console.getPointer(),new BaseTSD.SIZE_T(Native.POINTER_SIZE),null,null));
      var startup=new Startup();startup.base.cb=new WinDef.DWORD(startup.size());startup.attributes=attributes;
      var created=new WinBase.PROCESS_INFORMATION();
      String command=argv.stream().map(WindowsPty::quote).collect(java.util.stream.Collectors.joining(" "));
      var environment=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);environment.putAll(System.getenv());environment.put("SHELL","powershell");environment.put("TERM","xterm-256color");
      String block=environment.entrySet().stream().map(e->e.getKey()+"="+e.getValue()+"\0").collect(java.util.stream.Collectors.joining())+"\0";
      byte[] encoded=block.getBytes(StandardCharsets.UTF_16LE);var nativeEnvironment=new Memory(encoded.length);nativeEnvironment.write(0,encoded,0,encoded.length);
      require(Console.API.CreateProcessW(null,(command+'\0').toCharArray(),null,null,false,0x80000|0x400,nativeEnvironment,new WString(directory.toString()),startup,created));
      process=created.hProcess;Kernel32.INSTANCE.CloseHandle(created.hThread);
      drain=Thread.ofPlatform().daemon().start(()->{
        byte[] bytes=new byte[4096];var count=new IntByReference();
        while(Kernel32.INSTANCE.ReadFile(output,bytes,bytes.length,count,null))if(count.getValue()>0)transcript.append(bytes,count.getValue());
      });
    }catch(IOException|RuntimeException error){close();throw error;}
    finally{if(inputRead!=null)Kernel32.INSTANCE.CloseHandle(inputRead);if(outputWrite!=null)Kernel32.INSTANCE.CloseHandle(outputWrite);}
  }
  private static void require(boolean result)throws IOException {if(!result)throw new IOException("Windows PTY error "+Kernel32.INSTANCE.GetLastError());}
  private static String quote(String value) {
    StringBuilder result=new StringBuilder("\"");int slashes=0;
    for(char c:value.toCharArray()) {
      if(c=='\\'){slashes++;continue;}
      result.append("\\".repeat(c=='\"'?slashes*2+1:slashes));slashes=0;result.append(c);
    }
    return result.append("\\".repeat(slashes*2)).append('"').toString();
  }
  void send(String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);var written=new IntByReference();require(Kernel32.INSTANCE.WriteFile(input,bytes,bytes.length,written,null));if(written.getValue()!=bytes.length)throw new IOException("Partial PTY input");}
  String output(){return transcript.text().replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]","").replaceAll("\u001b\\][^\u0007]*\u0007","");}
  void await(String expected)throws Exception {awaitAfter(expected,0);}
  void awaitAfter(String expected,int offset)throws Exception {
    long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
    while(System.nanoTime()<deadline){String text=output();if(text.length()>=offset&&text.substring(offset).contains(expected))return;if(Kernel32.INSTANCE.WaitForSingleObject(process,0)==0)throw new AssertionError("PTY client exited "+exitCode()+" before "+expected+"; output: "+text);Thread.sleep(50);}
    throw new AssertionError("PTY missing "+expected+"; output: "+output());
  }
  int exitCode()throws IOException {if(Kernel32.INSTANCE.WaitForSingleObject(process,20000)!=0)throw new IOException("PTY client did not exit: "+output());var code=new IntByReference();require(Kernel32.INSTANCE.GetExitCodeProcess(process,code));return code.getValue();}
  @Override public void close() {
    if(closed)return;closed=true;
    if(console!=null)Console.API.ClosePseudoConsole(console);
    if(process!=null){Kernel32.INSTANCE.WaitForSingleObject(process,5000);Kernel32.INSTANCE.CloseHandle(process);}
    if(input!=null)Kernel32.INSTANCE.CloseHandle(input);
    if(drain!=null)try{drain.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    if(output!=null)Kernel32.INSTANCE.CloseHandle(output);
    if(attributes!=null){Console.API.DeleteProcThreadAttributeList(attributes);attributes.close();}
  }
  private static final class ByteArrayOutputStreamBuffer {
    private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
    synchronized void append(byte[] value,int count){bytes.write(value,0,count);}
    synchronized String text(){return bytes.toString(StandardCharsets.UTF_8);}
  }
}
