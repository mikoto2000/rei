package dev.mikoto2000.rei.storage;

import java.util.*;
import java.util.concurrent.*;
import com.sun.jna.*;
import com.sun.jna.ptr.*;

/** OS physical-disk idle-time counter, independent of application COMMIT counts. */
final class WindowsDiskSampler implements AutoCloseable {
  interface Pdh extends Library {
    int PdhOpenQueryW(WString source,Pointer user,PointerByReference query);
    int PdhAddEnglishCounterW(Pointer query,WString path,Pointer user,PointerByReference counter);
    int PdhCollectQueryData(Pointer query);
    int PdhGetFormattedCounterValue(Pointer counter,int format,IntByReference type,Value value);
    int PdhCloseQuery(Pointer query);
  }
  public static class Number extends Union { public double number;public long integer;public Pointer text; }
  @Structure.FieldOrder({"status","value"})
  public static class Value extends Structure { public int status;public Number value=new Number(); }
  final List<Double> active=new ArrayList<>();
  final List<Long> heap=new ArrayList<>();
  final String instance=System.getProperty("rei.io.disk",System.getenv().getOrDefault("REI_IO_DISK","_Total"));
  final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
  Pdh pdh;Pointer query,counter;String unavailable;boolean closed;
  WindowsDiskSampler() {
    try {
      if(!System.getProperty("os.name").startsWith("Windows"))throw new IllegalStateException("Windows only");
      pdh=Native.load("pdh",Pdh.class);
      var q=new PointerByReference();check(pdh.PdhOpenQueryW(null,null,q));query=q.getValue();
      var c=new PointerByReference();check(pdh.PdhAddEnglishCounterW(query,new WString("\\PhysicalDisk("+instance+")\\% Idle Time"),null,c));counter=c.getValue();
      check(pdh.PdhCollectQueryData(query));
      executor.scheduleAtFixedRate(this::sample,100,100,TimeUnit.MILLISECONDS);
    }catch(RuntimeException|LinkageError error){unavailable=error.toString();}
  }
  private static void check(int status){if(status!=0)throw new IllegalStateException("PDH status 0x"+Integer.toHexString(status));}
  synchronized void sample() {
    if(closed)return;
    try {
      check(pdh.PdhCollectQueryData(query));var value=new Value();value.value.setType(double.class);
      check(pdh.PdhGetFormattedCounterValue(counter,0x200,null,value));
      if(value.status!=0&&value.status!=1)throw new IllegalStateException("PDH sample status "+value.status);
      active.add(Math.max(0,Math.min(100,100-value.value.number)));
      var runtime=Runtime.getRuntime();heap.add(runtime.totalMemory()-runtime.freeMemory());
    }catch(RuntimeException error){unavailable=error.toString();}
  }
  synchronized Map<String,Object> summary() {
    var result=new LinkedHashMap<String,Object>();result.put("diskInstance",instance);result.put("diskSamples",active.size());
    if(!active.isEmpty()) {
      var values=active.stream().mapToDouble(Double::doubleValue).sorted().toArray();
      result.put("diskActiveMeanPercent",Arrays.stream(values).average().orElseThrow());
      result.put("diskActiveP95Percent",values[(int)Math.ceil(values.length*.95)-1]);
      result.put("heapMaxBytes",heap.stream().mapToLong(Long::longValue).max().orElseThrow());
    }else result.put("diskActiveUnavailable",unavailable==null?"trial shorter than sampling interval":unavailable);
    return result;
  }
  public void close() {
    executor.shutdownNow();
    synchronized(this){closed=true;if(query!=null)pdh.PdhCloseQuery(query);query=null;}
  }
}
