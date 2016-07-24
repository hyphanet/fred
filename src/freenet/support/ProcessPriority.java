/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/**
 * A class to control the global priority of the current process.
 * Microsoft suggests flagging daemon/server processes with the BACKGROUND_MODE
 * priority class so that they don't interfere with the responsiveness of the
 * rest of the system. This is especially important when freenet is started at
 * system startup.
 * We use JNA to call the OS libraries directly without needing JNI wrappers.
 * Its usage is really simple: just call ProcessPriority.backgroundMode(true).
 * If the OS doesn't support it or if the process doesn't have the appropriate
 * permissions, the above call is simply a no-op.
 *
 * @author Carlo Alberto Ferraris &lt;cafxx@strayorange.com&gt;
 *
 * TODO: emulate the BACKGROUND_MODE priority class on other OSes (linux, mac)
 */
 
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
        public static int ERROR_PROCESS_MODE_ALREADY_BACKGROUND = 402;
        public static int ERROR_PROCESS_MODE_NOT_BACKGROUND     = 403;
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
                else if (win.GetLastError() == Kernel32.ERROR_PROCESS_MODE_ALREADY_BACKGROUND)
                    throw new IllegalStateException();
        return background;
    }

    private static boolean exitBackgroundMode() throws Exception {
        if (!init())
            return false;
        if (background)
            if (Platform.isWindows())
                if (win.SetPriorityClass(win.GetCurrentProcess(), Kernel32.PROCESS_MODE_BACKGROUND_END))
                    return background = false;
                else if (win.GetLastError() == Kernel32.ERROR_PROCESS_MODE_NOT_BACKGROUND)
                    throw new IllegalStateException();
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

