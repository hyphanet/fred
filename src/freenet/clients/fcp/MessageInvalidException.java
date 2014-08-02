/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

/**
 * Thrown when an FCP message is invalid. This is after we have a
 * SimpleFieldSet; one example is if the fields necessary do not exist.
 * This is a catch-all error; it corresponds to MESSAGE_PARSE_ERROR on
 * ProtocolError.
 */
public class MessageInvalidException extends Exception {
	private static final long serialVersionUID = -1;

	final int protocolCode;
	public final String ident;
	public final boolean global;
	
	public MessageInvalidException(int protocolCode, String extra, String ident, boolean global) {
		super(extra);
		this.protocolCode = protocolCode;
		this.ident = ident;
		this.global = global;
	}

}
