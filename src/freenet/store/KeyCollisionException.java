/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store;

import freenet.support.LightweightException;
import freenet.support.Logger;

public class KeyCollisionException extends LightweightException {
	private static final long serialVersionUID = -1;
    private static volatile boolean logDEBUG;
    
    static { Logger.registerClass(KeyCollisionException.class); }
    
    @Override
    protected boolean shouldFillInStackTrace() {
        return logDEBUG;
    }
}
