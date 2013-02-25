/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store;

import freenet.support.Logger;

public class KeyCollisionException extends Exception {
	private static final long serialVersionUID = -1;
    private static volatile boolean logDEBUG;
    
    static { Logger.registerClass(KeyCollisionException.class); }
    
    // Optimization :
    // https://blogs.oracle.com/jrose/entry/longjumps_considered_inexpensive
	@Override
    public final synchronized Throwable fillInStackTrace() {
        if(logDEBUG)
            return super.fillInStackTrace();
        return null;
    }
}
