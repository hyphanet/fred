/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.clients.fcp.FCPConnectionHandler.DDACheckJob;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/**
 * client -> node: DDARequest { WantRead=true, WantWrite=true, Dir=/tmp/blah }
 * node -> client: DDAReply { Dir=/tmp/blah, ReadFilename=random1, WriteFilename=random2, ContentToWrite=random3 }
 * client -> node: DDAResponse { Dir=/tmp/blah, ReadContent=blah }
 * node -> client: DDAComplete { Dir=/tmp/blah, ReadAllowed=true, WriteAllowed=true }
 * 
 *  @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class TestDDARequestMessage extends FCPMessage {
	public static final String NAME = "TestDDARequest";
	public static final String DIRECTORY = "Directory";
	public static final String WANT_READ = "WantReadDirectory";
	public static final String WANT_WRITE = "WantWriteDirectory";
	
	final String identifier;
	final boolean wantRead, wantWrite;
	
	
	/** 
	 * @throws MessageInvalidException 
	 */
	public TestDDARequestMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get(DIRECTORY);
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Directory given!", null, false);
		if(identifier.length() == 0)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "The specified Directory can't be empty!", null, false);
		
		wantRead = fs.getBoolean(WANT_READ, false);
		wantWrite = fs.getBoolean(WANT_WRITE, false);
		if((wantRead == false) && (wantWrite == false))
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Both "+ WANT_READ + " and " + WANT_WRITE + " are set to false: what's the point of sending a message?", identifier, false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return null;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		DDACheckJob job;
		try {
			job = handler.enqueueDDACheck(identifier, wantRead, wantWrite);
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, e.getMessage(), identifier, false);
		}
		TestDDAReplyMessage reply = new TestDDAReplyMessage(job);
		handler.send(reply);
	}
	
}
