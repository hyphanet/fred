/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

public class KeyEncodeException extends Exception {
	private static final long serialVersionUID = -1;

	public KeyEncodeException(String string) {
		super(string);
	}

	public KeyEncodeException() {
		super();
	}

	public KeyEncodeException(String message, Throwable cause) {
		super(message, cause);
	}

	public KeyEncodeException(Throwable cause) {
		super(cause);
	}

}
