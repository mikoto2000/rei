package dev.mikoto2000.rei.voice;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Read-only MMDevice inventory. Native resources belong to one query and are released on every path. */
public final class WindowsAudioEndpoints {
  public record Endpoint(String id,String name,int state) {}
  public List<Endpoint> captureEndpoints() {
    if(!System.getProperty("os.name","").startsWith("Windows")||ADDRESS.byteSize()!=8)
      throw new IllegalStateException("Windows x64 endpoint monitoring is required");
    if(!WindowsAudioEndpoints.class.getModule().isNativeAccessEnabled())
      throw new IllegalStateException("Endpoint monitoring requires --enable-native-access=ALL-UNNAMED");
    try(var arena=Arena.ofConfined()) {
      var linker=Linker.nativeLinker();
      var library=SymbolLookup.libraryLookup(Path.of(System.getenv("SystemRoot"),"System32","Ole32.dll"),arena);
      var api=new Com(arena,linker,library);
      api.check(api.call(api.function("CoInitializeEx",JAVA_INT,ADDRESS,JAVA_INT),MemorySegment.NULL,0));
      try{return api.endpoints();}finally{api.call(api.function("CoUninitialize",null));}
    }
  }
  private static final class Com {
    final Arena arena;final Linker linker;final SymbolLookup library;
    Com(Arena arena,Linker linker,SymbolLookup library){this.arena=arena;this.linker=linker;this.library=library;}
    MethodHandle function(String name,MemoryLayout result,MemoryLayout...args) {
      return linker.downcallHandle(library.find(name).orElseThrow(),descriptor(result,args));
    }
    FunctionDescriptor descriptor(MemoryLayout result,MemoryLayout...args){return result==null?FunctionDescriptor.ofVoid(args):FunctionDescriptor.of(result,args);}
    Object call(MethodHandle handle,Object...args) {
      try{return handle.invokeWithArguments(args);}catch(Throwable failure){throw new IllegalStateException("Windows endpoint query failed",failure);}
    }
    void check(Object result){if((Integer)result<0)throw new IllegalStateException("Windows endpoint API failed (HRESULT "+Integer.toHexString((Integer)result)+")");}
    MethodHandle method(MemorySegment object,int slot,MemoryLayout...args) {
      var table=object.reinterpret(ADDRESS.byteSize()).get(ADDRESS,0);
      var address=table.reinterpret((slot+1)*ADDRESS.byteSize()).getAtIndex(ADDRESS,slot);
      var layouts=new MemoryLayout[args.length+1];layouts[0]=ADDRESS;System.arraycopy(args,0,layouts,1,args.length);
      return linker.downcallHandle(address,FunctionDescriptor.of(JAVA_INT,layouts));
    }
    void release(MemorySegment object){if(!object.equals(MemorySegment.NULL))call(method(object,2),object);}
    MemorySegment guid(String text) {
      var value=UUID.fromString(text);var segment=arena.allocate(16,4);long most=value.getMostSignificantBits(),least=value.getLeastSignificantBits();
      segment.set(JAVA_INT,0,(int)(most>>>32));segment.set(JAVA_SHORT,4,(short)(most>>>16));segment.set(JAVA_SHORT,6,(short)most);
      for(int i=0;i<8;i++)segment.set(JAVA_BYTE,8+i,(byte)(least>>>(56-i*8)));return segment;
    }
    String wideString(MemorySegment pointer) {
      if(pointer.equals(MemorySegment.NULL))throw new IllegalStateException("Windows endpoint string is missing");
      var chars=pointer.reinterpret(1024);var value=new StringBuilder();
      for(int i=0;i<512;i++){char c=chars.getAtIndex(JAVA_CHAR,i);if(c==0)return value.toString();value.append(c);}
      throw new IllegalStateException("Windows endpoint string exceeds the supported bound");
    }
    List<Endpoint> endpoints() {
      var output=arena.allocate(ADDRESS);MemorySegment enumerator=MemorySegment.NULL,collection=MemorySegment.NULL;
      try {
        check(call(function("CoCreateInstance",JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,ADDRESS,ADDRESS),guid("bcde0395-e52f-467c-8e3d-c4579291692e"),MemorySegment.NULL,1,guid("a95664d2-9614-4f35-a746-de8db63617e6"),output));
        enumerator=output.get(ADDRESS,0);output.set(ADDRESS,0,MemorySegment.NULL);
        check(call(method(enumerator,3,JAVA_INT,JAVA_INT,ADDRESS),enumerator,1,15,output));collection=output.get(ADDRESS,0);
        var count=arena.allocate(JAVA_INT);check(call(method(collection,3,ADDRESS),collection,count));int size=count.get(JAVA_INT,0);
        if(size<0||size>256)throw new IllegalStateException("Windows endpoint count exceeds the supported bound");
        var endpoints=new ArrayList<Endpoint>();
        for(int i=0;i<size;i++) {
          output.set(ADDRESS,0,MemorySegment.NULL);check(call(method(collection,4,JAVA_INT,ADDRESS),collection,i,output));
          var device=output.get(ADDRESS,0);
          try{endpoints.add(endpoint(device));}finally{release(device);}
        }
        return List.copyOf(endpoints);
      }finally{release(collection);release(enumerator);}
    }
    Endpoint endpoint(MemorySegment device) {
      var output=arena.allocate(ADDRESS);var state=arena.allocate(JAVA_INT);
      check(call(method(device,6,ADDRESS),device,state));
      check(call(method(device,5,ADDRESS),device,output));var idPointer=output.get(ADDRESS,0);String id;
      try{id=wideString(idPointer);}finally{call(function("CoTaskMemFree",null,ADDRESS),idPointer);}
      output.set(ADDRESS,0,MemorySegment.NULL);check(call(method(device,4,JAVA_INT,ADDRESS),device,0,output));
      var properties=output.get(ADDRESS,0);
      try {
        var key=arena.allocate(20,4);MemorySegment.copy(guid("a45c254e-df1c-4efd-8020-67d146a850e0"),0,key,0,16);key.set(JAVA_INT,16,14);
        var value=arena.allocate(24,8);
        try {
          check(call(method(properties,5,ADDRESS,ADDRESS),properties,key,value));
          if(value.get(JAVA_SHORT,0)!=31)throw new IllegalStateException("Windows endpoint friendly name has an unsupported type");
          return new Endpoint(id,wideString(value.get(ADDRESS,8)),state.get(JAVA_INT,0));
        }finally{check(call(function("PropVariantClear",JAVA_INT,ADDRESS),value));}
      }finally{release(properties);}
    }
  }
  public static void main(String[] args){new WindowsAudioEndpoints().captureEndpoints().forEach(System.out::println);}
}
