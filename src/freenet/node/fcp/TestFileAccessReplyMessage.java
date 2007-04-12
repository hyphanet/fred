package freenet.node.fcp;

import java.io.File;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileUtil;

/**
 * Tell the client what access the node has to a specific file
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * @see TestFileAccessQueryMessage
 * 
 * Shall we provide a salted hash of the content ? 
 * It's probably not necessary as a client can generate a random filename...
 */
public class TestFileAccessReplyMessage extends FCPMessage {
	public static final String name = "TestFileAccessReply";
	public static final String CAN_READ = "CanRead";
	public static final String CAN_WRITE = "CanWrite";
	public static final String IS_DDA_READ_ALLOWED = "IsDDAReadAllowed";
	public static final String IS_DDA_WRITE_ALLOWED = "IsDDAWriteAllowed";
	
	private final File file;
	private final FCPConnectionHandler handler;

	public TestFileAccessReplyMessage(FCPConnectionHandler handler, File filename) {
		this.file = FileUtil.getCanonicalFile(filename);
		this.handler = handler;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		
		sfs.putSingle(TestFileAccessQueryMessage.FILENAME, file.toString());
		sfs.putSingle(CAN_READ, String.valueOf(file.canRead()));
		sfs.putSingle(CAN_WRITE, String.valueOf(file.canWrite()));
		sfs.putSingle(IS_DDA_READ_ALLOWED, String.valueOf(handler.allowDDAFrom(file, false)));
		sfs.putSingle(IS_DDA_WRITE_ALLOWED, String.valueOf(handler.allowDDAFrom(file, true)));
		
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, name + " goes from server to client not the other way around", name, false);
	}

}
