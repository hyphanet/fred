/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

/**
 * Thrown when an FCP message is invalid. This is after we have a
 * SimpleFieldSet; one example is if the fields necessary do not exist.
 * This is a catch-all error; it corresponds to MESSAGE_PARSE_ERROR on
 * ProtocolError.
 */
public class MessageInvalidException extends Exception {
	private static final long serialVersionUID = -1;

	int protocolCode;
	public String ident;
	
	public MessageInvalidException(int protocolCode, String extra, String ident) {
		super(extra);
		this.protocolCode = protocolCode;
		this.ident = ident;
	}

}
