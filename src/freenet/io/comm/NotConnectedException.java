/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.support.LightweightException;

/**
 * @author amphibian
 * 
 * Exception thrown when we try to send a message to a node that is
 * not currently connected.
 */
public class NotConnectedException extends LightweightException {
    private static final long serialVersionUID = -1;
    public NotConnectedException(String string) {
        super(string);
    }

    public NotConnectedException() {
        super();
    }

    public NotConnectedException(DisconnectedException e) {
        super(e.toString());
        initCause(e);
    }
}
