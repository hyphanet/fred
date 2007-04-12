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
public class TestDDAReply extends FCPMessage {
	public static final String NAME = "TestDDAReply";
	public static final String READ_FILENAME = "ReadFilename";
	public static final String WRITE_FILENAME = "WriteFilename";
	public static final String CONTENT_TO_WRITE = "ContentToWrite";
	
	final DDACheckJob checkJob;
	
	TestDDAReply(DDACheckJob job) {
		this.checkJob = job;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle(TestDDARequest.DIRECTORY, checkJob.directory.toString());
		
		if(checkJob.readFilename != null) {
			sfs.putSingle(READ_FILENAME, checkJob.readFilename.toString());
		}
		
		if(checkJob.writeFilename != null) {
			sfs.putSingle(WRITE_FILENAME, checkJob.writeFilename.toString());
			sfs.putSingle(CONTENT_TO_WRITE, checkJob.writeContent);
		}
		
		return sfs;
	}

	public String getName() {
		return NAME;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, NAME + " goes from server to client not the other way around", NAME, false);
	}
}
