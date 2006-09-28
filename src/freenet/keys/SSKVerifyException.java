/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/**
 * Thrown when an SSK fails to verify at the node level.
 */
public class SSKVerifyException extends KeyVerifyException {
	private static final long serialVersionUID = -1;

	public SSKVerifyException(String string) {
		super(string);
	}

}
