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
 */
public class TestFileAccessReplyMessage extends FCPMessage {
	public static final String name = "TestFileAccessReply";
	public static final String CAN_READ = "CanRead";
	public static final String CAN_WRITE = "CanWrite";
	public static final String CAN_EXEC = "CanExecute";
	
	private final File file;

	public TestFileAccessReplyMessage(File filename) {
		this.file = FileUtil.getCanonicalFile(filename);
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		
		sfs.putSingle(TestFileAccessQueryMessage.FILENAME, file.toString());
		sfs.putSingle(CAN_READ, String.valueOf(file.canRead()));
		sfs.putSingle(CAN_WRITE, String.valueOf(file.canWrite()));
		sfs.putSingle(CAN_EXEC, String.valueOf(file.canExecute()));
		
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, name + " goes from server to client not the other way around", name, false);
	}

}
