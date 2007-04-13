/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.node.Node;
import freenet.node.fcp.FCPConnectionHandler.DDACheckJob;
import freenet.support.Logger;
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
public class TestDDACompleteMessage extends FCPMessage {
	public static String name = "TestDDAComplete";
	public static String READ_ALLOWED = "ReadAllowed";
	public static String WRITE_ALLOWED = "WriteAllowed";

	final DDACheckJob checkJob;
	final String readContentFromClient;
	private final FCPConnectionHandler handler;
	
	public TestDDACompleteMessage(FCPConnectionHandler handler, DDACheckJob job, String readContent) {
		this.checkJob = job;
		this.readContentFromClient = readContent;
		this.handler = handler;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		
		sfs.putSingle(TestDDARequestMessage.DIRECTORY, checkJob.directory.toString());
		
		boolean isReadAllowed = false; 
		boolean isWriteAllowed = false;
		
		if(checkJob.readFilename != null) {
			isReadAllowed = (readContentFromClient != null) &&  (checkJob.readContent.equals(readContentFromClient));
			// cleanup in any case : we created it!... let's hope the client will do the same on its side.
			checkJob.readFilename.delete();
			sfs.putSingle(READ_ALLOWED, String.valueOf(isReadAllowed));
		}
		
		if(checkJob.writeFilename != null) {
			File maybeWrittenFile = checkJob.writeFilename;
			if (maybeWrittenFile.exists() && maybeWrittenFile.isFile() && maybeWrittenFile.canRead()) {
				try {
					FileInputStream fis = new FileInputStream(maybeWrittenFile);
					BufferedInputStream bis = new BufferedInputStream(fis);
					InputStreamReader isr = new InputStreamReader(bis);
					StringBuffer sb = new StringBuffer();
					
					char[] buf = new char[1024];
					
					while(isr.ready()) {
						isr.read(buf);
						sb.append(buf);
					}
					isr.close();
					bis.close();
					fis.close();
					isWriteAllowed = checkJob.writeContent.equals(sb.toString().trim());
				} catch (IOException e) {
					Logger.error(this, "Caught an IOE trying to read the file (" + maybeWrittenFile + ")! " + e.getMessage());
				}
			}
			sfs.putSingle(WRITE_ALLOWED, String.valueOf(isWriteAllowed));
		}
		
		handler.registerTestDDAResult(checkJob.directory.toString(), isReadAllowed, isWriteAllowed);
		
		return sfs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, name + " goes from server to client not the other way around", name, false);
	}
}
