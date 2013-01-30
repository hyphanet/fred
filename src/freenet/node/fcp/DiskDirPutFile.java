/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;

public class DiskDirPutFile extends DirPutFile {

	final File file;
	
	public static DiskDirPutFile create(String name, String contentTypeOverride, SimpleFieldSet subset, 
			String identifier, boolean global) throws MessageInvalidException {
		String s = subset.get("Filename");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field: Filename on "+name, identifier, global);
		File file = new File(s);
		String mimeType;
		if(contentTypeOverride == null)
			mimeType = guessMIME(name, file);
		else
			mimeType = contentTypeOverride;
		return new DiskDirPutFile(name, mimeType, file);
	}
	
	// FIXME implement FileHash support
	public DiskDirPutFile(String name, String mimeType, File f) {
		super(name, mimeType);
 		this.file = f;
	}

	protected static String guessMIME(String name, File file) {
		String mime = DirPutFile.guessMIME(name);
		if(mime == null) {
			mime = DefaultMIMETypes.guessMIMEType(file.getName(), false /* fixme? */);
		}
		return mime;
	}

	@Override
	public Bucket getData() {
		return new FileBucket(file, true, false, false, false, false);
	}

	public File getFile() {
		return file;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(file);
		container.delete(this);
	}

}
