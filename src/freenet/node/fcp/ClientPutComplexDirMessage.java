/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.async.ManifestElement;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.BucketFactory;
import freenet.support.io.PersistentTempBucketFactory;

/**
 * <pre>
 * ClientPutComplexDir
 * &lt; ... standard ClientPutDir headers ... &gt;
 * Files.0.Name=hello.txt
 * Files.0.UploadFrom=direct
 * Files.0.Metadata.ContentType=text/plain
 * Files.0.DataLength=6
 *  ( upload the 6 bytes following this message as hello.txt, type plain text)
 * Files.1.Name=something.pdf
 * Files.1.UploadFrom=disk
 * Files.1.Filename=something.pdf
 *  ( upload something.pdf, guess the mime type from the filename )
 * Files.2.Name=toad.jpeg
 * Files.2.UploadFrom=redirect
 * Files.2.TargetURI=CHK@...,...,...
 * Files.2.Metadata.ContentType=image/jpeg
 *  ( not yet supported, but would be really useful! FIXME ! )
 * (note that the Files.x must always be a decimal integer. We use these for sort 
 *  order for UploadFrom=direct. they must be sequential and start at 0).
 * ...
 * End
 * &lt;data from above direct uploads, ***in alphabetical order***&gt;
 * </pre>
 */
public class ClientPutComplexDirMessage extends ClientPutDirMessage {

	/** The files attached to this message, in a directory hierarchy */
	private final HashMap<String, Object /* <HashMap || DirPutFile> */> filesByName;
	/** Any files we want to read data from */
	private final LinkedList<DirPutFile> filesToRead;
	/** Total number of bytes of attached data */
	private final long attachedBytes;
	
	public ClientPutComplexDirMessage(SimpleFieldSet fs, BucketFactory bfTemp, PersistentTempBucketFactory bfPersistent) throws MessageInvalidException {
		// Parse the standard ClientPutDir headers - URI, etc.
		super(fs);
		
		filesByName = new HashMap<String, Object>();
		filesToRead = new LinkedList<DirPutFile>();
		long totalBytes = 0;
		// Now parse the meat
		SimpleFieldSet files = fs.subset("Files");
		if(files == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Files section", identifier, global);
		boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		for(int i=0;;i++) {
			SimpleFieldSet subset = files.subset(Integer.toString(i));
			if(subset == null) break;
			DirPutFile f = DirPutFile.create(subset, identifier, global, (persistenceType == ClientRequest.PERSIST_FOREVER) ? bfPersistent : bfTemp);
			addFile(f);
			if(logMINOR) Logger.minor(this, "Adding "+f);
			if(f instanceof DirectDirPutFile) {
				totalBytes += ((DirectDirPutFile)f).bytesToRead();
				filesToRead.addLast(f);
				if(logMINOR) Logger.minor(this, "totalBytes now "+totalBytes);
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
	
	@SuppressWarnings("unchecked")
	private void addFile(HashMap<String, Object> byName, String name, DirPutFile f) throws MessageInvalidException {
		int idx = name.indexOf('/');
		if(idx == -1) {
			byName.put(name, f);
		} else {
			String before = name.substring(0, idx);
			String after = name.substring(idx+1);
			Object o = byName.get(before);
			if(o != null) {
				if (o instanceof HashMap) {
					addFile((HashMap<String, Object>) o, after, f);
					return;
				} else {
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Cannot be both a file and a directory: "+before, identifier, global);
				}
			} else {
				o = new HashMap<Object, Object>();
				byName.put(before, o);
				addFile((HashMap<String, Object>) o, after, f);
			}
		}
	}

	static final String NAME = "ClientPutComplexDir";
	
	@Override
	public String getName() {
		return NAME;
	}

	@Override
	long dataLength() {
		return attachedBytes;
	}

	String getIdentifier() {
		return identifier;
	}

	@Override
	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		for(DirPutFile f: filesToRead) {
			((DirectDirPutFile)f).read(is);
		}
	}

	@Override
	protected void writeData(OutputStream os) throws IOException {
		for(DirPutFile f: filesToRead) {
			((DirectDirPutFile)f).write(os);
		}
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		// Convert the hierarchical hashmap's of DirPutFile's to hierarchical hashmap's
		// of ManifestElement's.
		// Then simply create the ClientPutDir.
		HashMap<String, Object> manifestElements = new HashMap<String, Object>();
		convertFilesByNameToManifestElements(filesByName, manifestElements, node);
		handler.startClientPutDir(this, manifestElements, false);
	}

	/**
	 * Convert a hierarchy of HashMap's containing DirPutFile's into a hierarchy of
	 * HashMap's containing ManifestElement's.
	 */
	@SuppressWarnings("unchecked")
	private void convertFilesByNameToManifestElements(HashMap<String, Object> filesByName,
	        HashMap<String, Object> manifestElements, Node node) throws MessageInvalidException {
		
		for (Map.Entry<String, Object> entry : filesByName.entrySet()) {
			String tempName = entry.getKey();
			Object val = entry.getValue();
			if(val instanceof HashMap) {
				HashMap<String, Object> h = (HashMap<String, Object>) val;
				HashMap<String, Object> manifests = new HashMap<String, Object>();
				manifestElements.put(tempName, manifests);
				convertFilesByNameToManifestElements(h, manifests, node);
			} else {
				DirPutFile f = (DirPutFile) val;
				if(f instanceof DiskDirPutFile && !node.clientCore.allowUploadFrom(((DiskDirPutFile)f).getFile()))
					throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "Not allowed to upload "+((DiskDirPutFile) f).getFile(), identifier, global);
				ManifestElement e = f.getElement();
				manifestElements.put(tempName, e);
			}
		}
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		filesToRead.clear();
		container.activate(filesByName, Integer.MAX_VALUE);
		removeFrom(container, filesByName);
		container.delete(this);
	}

	private void removeFrom(ObjectContainer container, HashMap<String, Object> filesByName) {
		for(Object val: filesByName.values()) {
			if(val instanceof HashMap) {
				removeFrom(container, (HashMap<String, Object>) val);
			} else {
				((DirPutFile)val).removeFrom(container);
			}
		}
		container.delete(filesByName);
	}

}
