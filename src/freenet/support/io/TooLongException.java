/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;

/** Exception thrown by a LineReadingInputStream when a line is too long. */
public class TooLongException extends IOException {
	private static final long serialVersionUID = -1;

	TooLongException(String s) {
		super(s);
	}
}