/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * Tell the client what access the node has to a specific file
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 */
public class TestFileAccessQueryMessage extends FCPMessage {
	public static final String name = "TestFileAccessQuery";
	public static final String FILENAME = "Filename";
	
	private final String filename;
	
	public TestFileAccessQueryMessage(SimpleFieldSet sfs) throws MessageInvalidException {
		filename = sfs.get(FILENAME);
		if(filename == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Directory given!", null, false);
		if(filename.length() < 1)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "The specified " + FILENAME + " can't be empty!", null, false);
	}
	
	public SimpleFieldSet getFieldSet() {
		return null;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		File file = new File(filename);
		if(!file.exists())
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "The node isn't aware that \"" + filename + "\" exists!", filename, false);
		TestFileAccessReplyMessage reply = new TestFileAccessReplyMessage(file);
		handler.outputHandler.queue(reply);
	}

}
