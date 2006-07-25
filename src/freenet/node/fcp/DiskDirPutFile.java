package freenet.node.fcp;

import java.io.File;

import freenet.client.DefaultMIMETypes;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;
import freenet.support.io.FileBucket;

public class DiskDirPutFile extends DirPutFile {

	final File file;
	
	public DiskDirPutFile(SimpleFieldSet subset, String identifier) throws MessageInvalidException {
		super(subset, identifier);
		String s = subset.get("Filename");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field: Filename on "+name, identifier);
		file = new File(s);
	}

	protected String guessMIME() {
		String mime = super.guessMIME();
		if(mime == null) {
			mime = DefaultMIMETypes.guessMIMEType(file.getName());
		}
		return mime;
	}

	public Bucket getData() {
		return new FileBucket(file, true, false, false, false);
	}

}
