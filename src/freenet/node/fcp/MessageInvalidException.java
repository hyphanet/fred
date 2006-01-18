package freenet.node.fcp;

/**
 * Thrown when an FCP message is invalid. This is after we have a
 * SimpleFieldSet; one example is if the fields necessary do not exist.
 * This is a catch-all error; it corresponds to MESSAGE_PARSE_ERROR on
 * ProtocolError.
 */
public class MessageInvalidException extends Exception {

	int protocolCode;
	
	public MessageInvalidException(int protocolCode, String extra) {
		super(extra);
	}

}
