package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.SimpleFieldSet;

/**
 * ClientPutComplexDir
 * < ... standard ClientPutDir headers ... >
 * Files.1.Name=hello.txt
 * Files.1.UploadFrom=direct
 * Files.1.Metadata.ContentType=text/plain
 * Files.1.DataLength=6
 *  ( upload the 6 bytes following this message as hello.txt, type plain text)
 * Files.2.Name=something.pdf
 * Files.2.UploadFrom=disk
 * Files.2.Filename=something.pdf
 *  ( upload something.pdf, guess the mime type from the filename )
 * Files.AnythingNotIncludingADotInIt.Name=toad.jpeg
 * Files.AnythingNotIncludingADotInIt.UploadFrom=redirect
 * Files.AnythingNotIncludingADotInIt.TargetURI=CHK@...,...,...
 * Files.AnythingNotIncludingADotInIt.Metadata.ContentType=image/jpeg
 *  ( not yet supported, but would be really useful! FIXME ! )
 * ...
 */
public class ClientPutComplexDirMessage extends ClientPutDirMessage {

	/** The files attached to this message, in a directory hierarchy */
	private final HashMap filesByName;
	/** Any files we want to read data from */
	private final LinkedList filesToRead;
	/** Total number of bytes of attached data */
	private final long attachedBytes;
	
	public ClientPutComplexDirMessage(SimpleFieldSet fs, BucketFactory bfTemp, BucketFactory bfPersistent) throws MessageInvalidException {
		// Parse the standard ClientPutDir headers - URI, etc.
		super(fs);
		
		filesByName = new HashMap();
		filesToRead = new LinkedList();
		long totalBytes = 0;
		// Now parse the meat
		SimpleFieldSet files = fs.subset("Files");
		if(files == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Files section", identifier);
		Iterator i = files.directSubsetNameIterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			SimpleFieldSet subset = files.subset(name);
			DirPutFile f = DirPutFile.create(subset, identifier, (persistenceType == ClientRequest.PERSIST_FOREVER) ? bfPersistent : bfTemp);
			addFile(f);
			if(f instanceof DirectDirPutFile) {
				totalBytes += ((DirectDirPutFile)f).bytesToRead();
			}
		}
		attachedBytes = totalBytes;
	}
	
	/**
	 * Add a file to the filesByName.
	 * @throws MessageInvalidException 
	 */
	private void addFile(DirPutFile f) throws MessageInvalidException {
		addFile(filesByName, f.getName(), f);
	}
	
	private void addFile(HashMap byName, String name, DirPutFile f) throws MessageInvalidException {
		int idx = name.indexOf('/');
		if(idx == -1) {
			byName.put(name, f);
		} else {
			String before = name.substring(0, idx);
			String after = name.substring(idx+1);
			Object o = byName.get(before);
			if(o != null) {
				if(o instanceof HashMap) {
					addFile((HashMap)o, after, f);
					return;
				} else {
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Cannot be both a file and a directory: "+before, identifier);
				}
			}
		}
	}

	static final String name = "ClientPutComplexDir";
	
	public String getName() {
		return name;
	}

	long dataLength() {
		return attachedBytes;
	}

	String getIdentifier() {
		return identifier;
	}

	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		Iterator i = filesToRead.iterator();
		while(i.hasNext()) {
			DirectDirPutFile f = (DirectDirPutFile) i.next();
			f.read(is);
		}
	}

	protected void writeData(OutputStream os) throws IOException {
		Iterator i = filesToRead.iterator();
		while(i.hasNext()) {
			DirectDirPutFile f = (DirectDirPutFile) i.next();
			f.write(os);
		}
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		// TODO Auto-generated method stub
		
	}

}
