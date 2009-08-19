/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

/**
 * The given codec identifier was invalid (number out of range, or misstyped
 * name)
 */
public class InvalidCompressionCodecException extends Exception {

	private static final long serialVersionUID = -1;

	public InvalidCompressionCodecException(String message) {
		super(message);
	}

}
