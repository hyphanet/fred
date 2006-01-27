package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * ProtocolError (some problem parsing the other side's FCP messages)
 * 
 * ProtocolError
 * Code=1
 * CodeDescription=ClientHello must be first message
 * ExtraDescription=Duh
 * Fatal=false // means the connection stays open
 * EndMessage
 */
public class ProtocolErrorMessage extends FCPMessage {

	static final int CLIENT_HELLO_MUST_BE_FIRST_MESSAGE = 1;
	static final int NO_LATE_CLIENT_HELLOS = 2;
	static final int MESSAGE_PARSE_ERROR = 3;
	static final int URI_PARSE_ERROR = 4;
	static final int MISSING_FIELD = 5;
	static final int ERROR_PARSING_NUMBER = 6;
	static final int INVALID_MESSAGE = 7;
	static final int INVALID_FIELD = 8;
	
	final int code;
	final String extra;
	final boolean fatal;
	
	private String codeDescription() {
		switch(code) {
		case CLIENT_HELLO_MUST_BE_FIRST_MESSAGE:
			return "ClientHello must be first message";
		case NO_LATE_CLIENT_HELLOS:
			return "No late ClientHello's accepted";
		case MESSAGE_PARSE_ERROR:
			return "Unknown message parsing error";
		case URI_PARSE_ERROR:
			return "Error parsing URI";
		case MISSING_FIELD:
			return "Missing field";
		case ERROR_PARSING_NUMBER:
			return "Error parsing a numeric field";
		case INVALID_MESSAGE:
			return "Don't know what to do with message";
		case INVALID_FIELD:
			return "Invalid field value";
		default:
			Logger.error(this, "Unknown error code: "+code, new Exception("debug"));
		return "(Unknown)";
		}
	}

	public ProtocolErrorMessage(int code, boolean fatal, String extra) {
		this.code = code;
		this.extra = extra;
		this.fatal = fatal;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet();
		sfs.put("Code", Integer.toString(code));
		sfs.put("CodeDescription", codeDescription());
		if(extra != null)
			sfs.put("ExtraDescription", extra);
		sfs.put("Fatal", Boolean.toString(fatal));
		return sfs;
	}

	public void run(FCPConnectionHandler handler, Node node) {
		Logger.error(this, "Client reported protocol error");
		if(fatal) handler.close();
	}

	public String getName() {
		return "ProtocolError";
	}

}
