/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * Thown when we can't parse a string to a Peer.
 * @author amphibian
 */
public class PeerParseException extends Exception {
	private static final long serialVersionUID = -1;
    public PeerParseException(Exception e) {
        super(e);
    }

    public PeerParseException() {
        super();
    }

	public PeerParseException(String string) {
		super(string);
	}

}
