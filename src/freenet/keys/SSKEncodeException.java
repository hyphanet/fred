/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

public class SSKEncodeException extends KeyEncodeException {
	private static final long serialVersionUID = -1;

	public SSKEncodeException(String message, KeyEncodeException e) {
		super(message, e);
	}

}
