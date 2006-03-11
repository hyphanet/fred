package freenet.node.fcp;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.support.BucketFactory;
import freenet.support.SimpleFieldSet;

/**
 * A request to upload a file to a manifest.
 * A ClientPutComplexDir will contain many of these.
 */
abstract class DirPutFile {

	final String name;
	final ClientMetadata meta;
	
	public DirPutFile(SimpleFieldSet subset, String identifier) throws MessageInvalidException {
		this.name = subset.get("Name");
		if(name == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Name", identifier);
		String contentTypeOverride = subset.get("Metadata.ContentType");
		if(contentTypeOverride != null) {
			meta = new ClientMetadata(contentTypeOverride);
		} else {
			meta = new ClientMetadata(guessMIME());
		}
	}

	protected String guessMIME() {
		// Guess it just from the name
		return DefaultMIMETypes.guessMIMEType(name);
	}

	/**
	 * Create a DirPutFile from a SimpleFieldSet.
	 */
	public static DirPutFile create(SimpleFieldSet subset, String identifier, BucketFactory bf) throws MessageInvalidException {
		String type = subset.get("UploadFrom");
		if(type == null || type.equalsIgnoreCase("direct")) {
			return new DirectDirPutFile(subset, identifier, bf);
		} else if(type.equalsIgnoreCase("disk")) {
			return new DiskDirPutFile(subset, identifier);
		} else {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unsupported or unknown UploadFrom: "+type, identifier);
		}
	}

	public String getName() {
		return name;
	}

}
