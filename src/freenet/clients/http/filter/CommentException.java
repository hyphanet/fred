/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

/**
 * Thrown when a filter operation cannot complete and the filter has produced some error output to help guide the user in
 * resolving the situation.
 * 
 * Note that the message is not yet encoded, and may contain data-dependant information; that is the responsibility of the 
 * catcher.
 */
public class CommentException extends Exception {
	
	private static final long serialVersionUID = 1L;

	public CommentException(String msg) {
		super(msg);
	}

}
