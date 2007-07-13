/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * Exception thrown when a caller attempts to use opennet
 * functionality, but it is not currently enabled in the node.
 */
public class OpennetDisabledException extends Exception {
	private static final long serialVersionUID = -1;
    public OpennetDisabledException(Exception e) {
        super(e);
    }
    
    public OpennetDisabledException(String msg) {
        super(msg);
    }

}
