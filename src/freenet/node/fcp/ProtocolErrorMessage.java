/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.support.Logger;

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
	static final int FREENET_URI_PARSE_ERROR = 4;
	static final int MISSING_FIELD = 5;
	static final int ERROR_PARSING_NUMBER = 6;
	public static final int INVALID_MESSAGE = 7;
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
	static final int ACCESS_DENIED = 24;
	static final int DIRECT_DISK_ACCESS_DENIED = 25;
	static final int COULD_NOT_READ_FILE = 26;
	static final int REF_SIGNATURE_INVALID = 27;
	static final int CANNOT_PEER_WITH_SELF = 28;
	static final int DUPLICATE_PEER_REF = 29;
	static final int OPENNET_DISABLED = 30;
	static final int DARKNET_ONLY = 31;
	static final int NO_SUCH_PLUGIN = 32;
	static final int PERSISTENCE_DISABLED = 33;
	static final int TOO_MANY_FILES_IN_INSERT = 34;
	static final int BAD_MIME_TYPE = 35;
	
	final int code;
	final String extra;
	final boolean fatal;
	final String ident;
	final boolean global;
	
	public ProtocolErrorMessage(int code, boolean fatal, String extra, String ident, boolean global) {
		this.code = code;
		this.extra = extra;
		this.fatal = fatal;
		this.ident = ident;
		this.global = global;
	}

	@Override
	public String toString() {
		return super.toString()+":"+code+":"+extra+":"+fatal+":"+ident+":"+global;
	}

}
