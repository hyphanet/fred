/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.fcp.FCPConnectionHandler.DDACheckJob;
import freenet.support.SimpleFieldSet;

/**
 * client -> node: DDARequest { WantRead=true, WantWrite=true, Dir=/tmp/blah }
 * node -> client: DDAReply { Dir=/tmp/blah, ReadFilename=random1, WriteFilename=random2, ContentToWrite=random3 }
 * client -> node: DDAResponse { Dir=/tmp/blah, ReadContent=blah }
 * node -> client: DDAComplete { Dir=/tmp/blah, ReadAllowed=true, WriteAllowed=true }
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 */
public class TestDDAResponseMessage extends FCPMessage {
	public static final String NAME = "TestDDAResponse";
	public static final String READ_CONTENT = "ReadContent";
	
	final String identifier;
	final String readContent;
	
	public TestDDAResponseMessage(SimpleFieldSet sfs) throws MessageInvalidException {
		identifier = sfs.get(TestDDARequestMessage.DIRECTORY);
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Directory given!", null, false);
		if(identifier.length() == 0)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "The specified Directory can't be empty!", null, false);
		
		readContent = sfs.get(READ_CONTENT);
	}

	public SimpleFieldSet getFieldSet() {
		return null;
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		DDACheckJob job;
		try {
			 job = handler.popDDACheck(identifier);
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, e.getMessage(), identifier, false);
		}
		if(job == null)
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "The node doesn't know that testDDA identifier! double check it! (" + identifier + ").", identifier, false);
		else if((job.readFilename != null) && (readContent == null))
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "You need to send " + READ_CONTENT + " back to the node if you specify " + TestDDARequestMessage.WANT_READ + " in " + TestDDARequestMessage.NAME + '.', identifier, false);
		
		TestDDACompleteMessage reply = new TestDDACompleteMessage(handler, job, readContent);
		handler.outputHandler.queue(reply);
	}
}
