package freenet.support;

import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;

public class ProcessPriority {
    private static boolean inited = false;
    private static boolean background = false;
    
    /// Windows interface (kernel32.dll) ///

    private interface Kernel32 extends StdCallLibrary {
        /* HANDLE -> Pointer, DWORD -> int */
        public boolean SetPriorityClass(Pointer hProcess, int dwPriorityClass);
        public Pointer GetCurrentProcess();
        public int GetLastError();

        public static int PROCESS_MODE_BACKGROUND_BEGIN         = 0x00100000;
        public static int PROCESS_MODE_BACKGROUND_END           = 0x00200000;
        public static int ERROR_THREAD_MODE_ALREADY_BACKGROUND  = 402;
        public static int ERROR_THREAD_MODE_NOT_BACKGROUND      = 403;
    }

    private static Kernel32 win = null;
    
    /// Implementation

    private static boolean init() {
        if (!inited) {
            try {
                if (Platform.isWindows()) {
                    win = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
                    inited = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return inited;
    }
    
    private static boolean enterBackgroundMode() throws Exception {
        if (!init())
            return false;
        if (!background)
            if (Platform.isWindows())
                if (win.SetPriorityClass(win.GetCurrentProcess(), Kernel32.PROCESS_MODE_BACKGROUND_BEGIN))
                    return background = true;
                else if (win.GetLastError() == Kernel32.ERROR_THREAD_MODE_ALREADY_BACKGROUND)
                    throw new Exception("Illegal state");
        return background;
    }

    private static boolean exitBackgroundMode() throws Exception {
        if (!init())
            return false;
        if (background)
            if (Platform.isWindows())
                if (win.SetPriorityClass(win.GetCurrentProcess(), Kernel32.PROCESS_MODE_BACKGROUND_END))
                    return background = false;
                else if (win.GetLastError() == Kernel32.ERROR_THREAD_MODE_NOT_BACKGROUND)
                    throw new Exception("Illegal state");
        return background;
    }
    
    /// Public methods ///////////////////////////////////
    
    public static boolean isBackgroundMode() {
    	return background;
    }

    public static boolean backgroundMode(boolean bg) {
    	try {
    		if (bg)
    			enterBackgroundMode();
    		else
    			exitBackgroundMode();
    	} catch (Exception e) {
    	}
    	return isBackgroundMode();
    }
}

