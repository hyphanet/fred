/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/**
 * Base class for decode exceptions.
 */
public class KeyDecodeException extends Exception {
	private static final long serialVersionUID = -1;
	public KeyDecodeException(String message) {
		super(message);
	}

	public KeyDecodeException() {
		super();
	}

	public KeyDecodeException(String message, Throwable cause) {
		super(message, cause);
	}

	public KeyDecodeException(Throwable cause) {
		super(cause);
	}

}
