/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

/**
 * Thrown when a synchronous FCP call failed. This allow callers to re-send certain messages if processing failed at the client/server.
 * @author xor (xor@freenetproject.org)
 */
public final class FCPCallFailedException extends Exception {

    private static final long serialVersionUID = 1L;

    public FCPCallFailedException() {
        super();
    }

    public FCPCallFailedException(Throwable cause) {
        super(cause);
    }

}
