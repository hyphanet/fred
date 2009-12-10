/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

/**
 *
 * @author unknown
 */
public class CannotCreateFromFieldSetException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 *
	 * @param msg
	 */
	public CannotCreateFromFieldSetException(String msg) {
		super(msg);
	}

	/**
	 *
	 * @param msg
	 * @param e
	 */
	public CannotCreateFromFieldSetException(String msg, Exception e) {
		super(msg + " : " + e, e);
	}

}
