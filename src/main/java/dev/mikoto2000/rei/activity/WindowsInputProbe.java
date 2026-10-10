package dev.mikoto2000.rei.activity;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.*;

/** Read-only, current-session signals. Lazy native loading keeps other platforms usable. */
final class WindowsInputProbe {
  interface User extends StdCallLibrary {
    boolean GetLastInputInfo(LastInput value);
    Pointer GetForegroundWindow();
    Pointer OpenInputDesktop(int flags,boolean inherit,int access);
    boolean GetUserObjectInformationW(Pointer object,int index,Pointer buffer,int bytes,IntByReference needed);
    boolean CloseDesktop(Pointer desktop);
  }
  interface Kernel extends StdCallLibrary {long GetTickCount64();boolean QueryUnbiasedInterruptTime(LongByReference working);}
  @Structure.FieldOrder({"cbSize","dwTime"})
  public static class LastInput extends Structure {
    public int cbSize,dwTime;
    public LastInput(){cbSize=size();}
  }
  private static final class Api {
    static final User USER=Native.load("user32",User.class,W32APIOptions.DEFAULT_OPTIONS);
    static final Kernel KERNEL=Native.load("kernel32",Kernel.class,W32APIOptions.DEFAULT_OPTIONS);
  }
  static DesktopActivityObserver.Lightweight read() {
    if(!Platform.isWindows())return null;
    var input=new LastInput();
    boolean valid=Api.USER.GetLastInputInfo(input);
    long ticks=Api.KERNEL.GetTickCount64();
    var working=new LongByReference();valid &= Api.KERNEL.QueryUnbiasedInterruptTime(working);
    var foreground=Api.USER.GetForegroundWindow();
    String desktop=null;
    var handle=Api.USER.OpenInputDesktop(0,false,1); // DESKTOP_READOBJECTS, never switch desktops.
    if(handle!=null)try(var buffer=new Memory(512)) {
      if(Api.USER.GetUserObjectInformationW(handle,2,buffer,512,new IntByReference()))desktop=buffer.getWideString(0);
    }finally{Api.USER.CloseDesktop(handle);}
    return new DesktopActivityObserver.Lightweight(Integer.toUnsignedLong(input.dwTime),ticks,
        foreground==null?null:Long.toUnsignedString(Pointer.nativeValue(foreground)),desktop,
        "Winlogon".equalsIgnoreCase(desktop),valid && foreground!=null && desktop!=null,working.getValue()/10000);
  }
}
