/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * ProtocolError (some problem parsing the other side's FCP messages, or other
 * problem not related to Freenet)
 * 
 * ProtocolError
 * Code=1
 * CodeDescription=ClientHello must be first message
 * ExtraDescription=Duh
 * Fatal=false // means the connection stays open
 * [Identifier=<ident> if we managed to parse one]
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
	static final int FILE_NOT_FOUND = 9;
	static final int DISK_TARGET_EXISTS = 10;
	static final int FILENAME_AND_TEMP_FILENAME_MUST_BE_IN_SAME_DIR = 11;
	static final int COULD_NOT_CREATE_FILE = 12;
	static final int COULD_NOT_WRITE_FILE = 13;
	static final int COULD_NOT_RENAME_FILE = 14;
	static final int NO_SUCH_IDENTIFIER = 15;
	static final int NOT_SUPPORTED = 16;
	static final int INTERNAL_ERROR = 17;
	static final int SHUTTING_DOWN = 18;
	static final int NO_SUCH_NODE_IDENTIFIER = 19;  // Unused
	static final int URL_PARSE_ERROR = 20;
	static final int REF_PARSE_ERROR = 21;
	static final int FILE_PARSE_ERROR = 22;
	static final int NOT_A_FILE_ERROR = 23;
	
	final int code;
	final String extra;
	final boolean fatal;
	final String ident;
	final boolean global;
	
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
		case FILE_NOT_FOUND:
			return "File not found, not a file or not readable";
		case DISK_TARGET_EXISTS:
			return "Disk target exists, refusing to overwrite for security reasons";
		case FILENAME_AND_TEMP_FILENAME_MUST_BE_IN_SAME_DIR:
			return "Filename and temp filename must be in same directory (so can rename)";
		case COULD_NOT_CREATE_FILE:
			return "Could not create file";
		case COULD_NOT_WRITE_FILE:
			return "Could not write file";
		case COULD_NOT_RENAME_FILE:
			return "Could not rename file";
		case NO_SUCH_IDENTIFIER:  // Unused
			return "No such identifier";
		case NOT_SUPPORTED:
			return "Not supported";
		case INTERNAL_ERROR:
			return "Internal error";
		case SHUTTING_DOWN:
			return "Shutting down";
		case NO_SUCH_NODE_IDENTIFIER:
			return "No such nodeIdentifier";
		case URL_PARSE_ERROR:
			return "Error parsing URL";
		case REF_PARSE_ERROR:
			return "Reference could not be parsed";
		case FILE_PARSE_ERROR:
			return "File could not be read";
		case NOT_A_FILE_ERROR:
			return "Filepath is not a file";
		default:
			Logger.error(this, "Unknown error code: "+code, new Exception("debug"));
		return "(Unknown)";
		}
	}

	public ProtocolErrorMessage(int code, boolean fatal, String extra, String ident, boolean global) {
		this.code = code;
		this.extra = extra;
		this.fatal = fatal;
		this.ident = ident;
		this.global = global;
	}

	public ProtocolErrorMessage(SimpleFieldSet fs) {
		ident = fs.get("Identifier");
		code = Integer.parseInt(fs.get("Code"));
		extra = fs.get("ExtraDescription");
		fatal = Fields.stringToBool(fs.get("Fatal"), false);
		global = Fields.stringToBool(fs.get("Global"), false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		if(ident != null)
			sfs.putSingle("Identifier", ident);
		sfs.put("Code", code);
		sfs.putSingle("CodeDescription", codeDescription());
		if(extra != null)
			sfs.putSingle("ExtraDescription", extra);
		sfs.put("Fatal", fatal);
		if(global)
			sfs.putSingle("Global", "true");
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
