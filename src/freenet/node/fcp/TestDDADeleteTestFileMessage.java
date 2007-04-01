/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import freenet.node.Node;
import freenet.node.TestDDAManager;
import freenet.support.SimpleFieldSet;

/**
 * DeleteDDATestFile
 * Identifier=indent123unique	[mandatory]
 * ClientToken=clientid         [mandatory]			test owner
 * 
 */
public class TestDDADeleteTestFileMessage extends FCPMessage {
	
	static final String name = "DeleteDDATestFile";
	
	private static final String FN_IDENTIFIER = "Identifier";
	
	private final String _ident; // unique id
	
	/** 
	 * @throws MessageInvalidException 
	 */
	public TestDDADeleteTestFileMessage(SimpleFieldSet fs) throws MessageInvalidException {
		_ident = fs.get(FN_IDENTIFIER);
		if(_ident == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, false);
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
		handler.getClientName();
		TestDDAManager tm = null;// FIXME node.clientCore.testDDAManager;
		TestDDAResult tr = tm.getTestResult(_ident);
		if (tr == null) {
			throw new MessageInvalidException(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, "No such test identifier", _ident, false);
		}
		if (!tr.isConfirmed) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "You need to confirm the test first", _ident, false);
		}
		File tf = tm.getTestWriteFile(_ident);
		
		FCPMessage msg;
		
		if (tf == null) {
			msg = new ProtocolErrorMessage(ProtocolErrorMessage.INTERNAL_ERROR, false, "Not a write test", _ident, false); 
		}
		
		handler.outputHandler.queue(this);
	}
}
