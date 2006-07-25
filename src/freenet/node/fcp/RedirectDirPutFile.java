package freenet.node.fcp;

import java.net.MalformedURLException;

import freenet.client.ClientMetadata;
import freenet.client.async.ManifestElement;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Bucket;

public class RedirectDirPutFile extends DirPutFile {

	final FreenetURI targetURI;
	
	public RedirectDirPutFile(SimpleFieldSet subset, String identifier) throws MessageInvalidException {
		super(subset, identifier);
		String target = subset.get("TargetURI");
		if(target == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier);
		try {
			targetURI = new FreenetURI(target);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier);
		}
		Logger.minor(this, "targetURI = "+targetURI);
		super.meta = new ClientMetadata();
	}

	public Bucket getData() {
		return null;
	}

	public ManifestElement getElement() {
		return new ManifestElement(name, targetURI, getMIMEType());
	}
}
