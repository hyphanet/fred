/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;

import com.sun.jna.Native;
import com.sun.jna.Platform;

import com.sun.jna.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinDef.DWORD;

/**
 * A class to control the global priority of the current process.
 * Microsoft suggests flagging daemon/server processes with the BELOW_NORMAL_PRIORITY_CLASS
 * priority class so that they don't interfere with the responsiveness of the
 * rest of the system. This is especially important when freenet is started at
 * system startup.
 * We use JNA to call the OS libraries directly without needing JNI wrappers.
 * Its usage is really simple: just call ProcessPriority.enterBackgroundMode().
 * If the OS doesn't support it or if the process doesn't have the appropriate
 * permissions, the above call is simply a no-op.
 *
 */
 
public class ProcessPriority {
    private static volatile boolean background = false;
    
    /// Windows interface (kernel32.dll) ///
    public interface WindowsHolder extends StdCallLibrary {
        WindowsHolder INSTANCE = (WindowsHolder) Native.loadLibrary("kernel32", WindowsHolder.class);

        boolean SetPriorityClass(HANDLE hProcess, DWORD dwPriorityClass);
        HANDLE GetCurrentProcess();
        DWORD GetLastError();

        DWORD BELOW_NORMAL_PRIORITY_CLASS           = new DWORD(0x00004000);
    }

    private static class LinuxHolder {
        static { Native.register(Platform.C_LIBRARY_NAME); }

        private static native int setpriority(int which, int who, int prio);
        final static int PRIO_PROCESS = 0;
        final static int MYSELF = 0;
        final static int LOWER_PRIORITY = 10;
    }

    private static class OSXHolder {
        static { Native.register(Platform.C_LIBRARY_NAME); }

        private static native int setpriority(int which, int who, int prio);
        final static int PRIO_DARWIN_THREAD = 3;
        final static int MYSELF = 0;
        final static int PRIO_DARWIN_NORMAL = 0;
        final static int PRIO_DARWIN_BG = 0x1000;
    }


    public static boolean enterBackgroundMode() {
        if (!background) {
            if (Platform.isWindows()) {
                WindowsHolder lib = WindowsHolder.INSTANCE;

                if (lib.SetPriorityClass(lib.GetCurrentProcess(), WindowsHolder.BELOW_NORMAL_PRIORITY_CLASS)) {
                    System.out.println("SetPriorityClass() succeeded!");
                    return background = true;
                } else {
                    System.err.println("SetPriorityClass() failed :"+lib.GetLastError());
                    return false;
                }
            } else if (Platform.isLinux()) {
                return handleReturn(LinuxHolder.setpriority(LinuxHolder.PRIO_PROCESS, LinuxHolder.MYSELF, LinuxHolder.LOWER_PRIORITY));

            } else if (Platform.isMac()) {
                return handleReturn(OSXHolder.setpriority(OSXHolder.PRIO_DARWIN_THREAD, OSXHolder.MYSELF, OSXHolder.PRIO_DARWIN_BG));
            }
        }
        return background;
    }

    private static boolean handleReturn(int ret) {
        if (ret == 0) {
            System.out.println("setpriority() succeeded!");
            return background = true;
        } else {
            System.err.println("setpriority() failed :"+ret);
            return false;
        }
    }

}

