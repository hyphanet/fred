/*
  ClientPutComplexDirMessage.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.client.async.ManifestElement;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.BucketFactory;
import freenet.support.io.PersistentTempBucketFactory;

/**
 * ClientPutComplexDir
 * < ... standard ClientPutDir headers ... >
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
 * <data from above direct uploads, ***in alphabetical order***>
 */
public class ClientPutComplexDirMessage extends ClientPutDirMessage {

	/** The files attached to this message, in a directory hierarchy */
	private final HashMap filesByName;
	/** Any files we want to read data from */
	private final LinkedList filesToRead;
	/** Total number of bytes of attached data */
	private final long attachedBytes;
	
	public ClientPutComplexDirMessage(SimpleFieldSet fs, BucketFactory bfTemp, PersistentTempBucketFactory bfPersistent) throws MessageInvalidException {
		// Parse the standard ClientPutDir headers - URI, etc.
		super(fs);
		
		filesByName = new HashMap();
		filesToRead = new LinkedList();
		long totalBytes = 0;
		// Now parse the meat
		SimpleFieldSet files = fs.subset("Files");
		if(files == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing Files section", identifier);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		for(int i=0;;i++) {
			SimpleFieldSet subset = files.subset(Integer.toString(i));
			if(subset == null) break;
			DirPutFile f = DirPutFile.create(subset, identifier, (persistenceType == ClientRequest.PERSIST_FOREVER) ? bfPersistent : bfTemp);
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
			} else {
				o = new HashMap();
				byName.put(before, o);
				addFile((HashMap)o, after, f);
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
		// Convert the hierarchical hashmap's of DirPutFile's to hierarchical hashmap's
		// of ManifestElement's.
		// Then simply create the ClientPutDir.
		HashMap manifestElements = new HashMap();
		convertFilesByNameToManifestElements(filesByName, manifestElements);
		handler.startClientPutDir(this, manifestElements);
	}

	/**
	 * Convert a hierarchy of HashMap's containing DirPutFile's into a hierarchy of
	 * HashMap's containing ManifestElement's.
	 */
	private void convertFilesByNameToManifestElements(HashMap filesByName, HashMap manifestElements) {
		Iterator i = filesByName.keySet().iterator();
		while(i.hasNext()) {
			String tempName = (String) (i.next());
			Object val = filesByName.get(tempName);
			if(val instanceof HashMap) {
				HashMap h = (HashMap) val;
				HashMap manifests = new HashMap();
				manifestElements.put(tempName, manifests);
				convertFilesByNameToManifestElements(h, manifests);
			} else {
				DirPutFile f = (DirPutFile) val;
				ManifestElement e = f.getElement();
				manifestElements.put(tempName, e);
			}
		}
	}

}
