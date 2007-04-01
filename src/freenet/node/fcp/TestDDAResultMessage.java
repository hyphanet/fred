/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * TestDDA
 * Identifier=indent123unique	[mandatory]
 * DirToTest=/path/to/dir       [mandatory]			the dir to test
 * TestList=true 		[default: false]  			can we list the dir?
 * TestRead=fileindir.ext			the filename for read test
 * 									readtest skipped if missing
 * TestWrite=true					the node will generate an unique filename
 *									writetest skipped if missing or not true
 * 
 */
public class TestDDAResultMessage extends FCPMessage {
	
	static final String name = "TestDDAResult";
	
	private static final String FN_IDENTIFIER = "Identifier";

	private final String _ident; // unique id
	private final String _msg; 

	/** 
	 * @throws MessageInvalidException 
	 */
//	public TestDDAResultMessage(SimpleFieldSet fs) throws MessageInvalidException {
//		_ident = fs.get(FN_IDENTIFIER);
//		if(_ident == null)
//			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, false);
//	
//	}

	private TestDDAResultMessage(String ident, String msg) {
		_ident = ident;
		_msg = msg;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", _ident);
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SimpleProgress goes from server to client not the other way around", _ident, false);
	}

}
