/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.async.ManifestElement;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A request to upload a file to a manifest.
 * A ClientPutComplexDir will contain many of these.
 */
abstract class DirPutFile {

	final String name;
	ClientMetadata meta;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	protected DirPutFile(String name, String mimeType) {
		this.name = name;
		meta = new ClientMetadata(mimeType);
	}

	protected static String guessMIME(String name) {
		// Guess it just from the name
		return DefaultMIMETypes.guessMIMEType(name, true);
	}

	/**
	 * Create a DirPutFile from a SimpleFieldSet.
	 */
	public static DirPutFile create(SimpleFieldSet subset, String identifier, boolean global, BucketFactory bf) throws MessageInvalidException {
		String name = subset.get("Name");
		if(name == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Name", identifier, global);
		String contentTypeOverride = subset.get("Metadata.ContentType");
		if(contentTypeOverride != null && (!contentTypeOverride.equals("")) && !DefaultMIMETypes.isPlausibleMIMEType(contentTypeOverride)) {
			throw new MessageInvalidException(ProtocolErrorMessage.BAD_MIME_TYPE, "Bad MIME type in Metadata.ContentType", identifier, global);
		}
		String type = subset.get("UploadFrom");
		if((type == null) || type.equalsIgnoreCase("direct")) {
			return DirectDirPutFile.create(name, contentTypeOverride, subset, identifier, global, bf);
		} else if(type.equalsIgnoreCase("disk")) {
			return DiskDirPutFile.create(name, contentTypeOverride, subset, identifier, global);
		} else if(type.equalsIgnoreCase("redirect")) {
			return RedirectDirPutFile.create(name, contentTypeOverride, subset, identifier, global);
		} else {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Unsupported or unknown UploadFrom: "+type, identifier, global);
		}
	}

	public String getName() {
		return name;
	}

	public String getMIMEType() {
		return meta.getMIMEType();
	}

	public abstract Bucket getData();

	public ManifestElement getElement() {
		String n = name;
		int idx = n.lastIndexOf('/');
		if(idx != -1) n = n.substring(idx+1);
		if(logMINOR)
			Logger.minor(this, "Element name: "+name+" -> "+n);
		return new ManifestElement(n, getData(), getMIMEType(), getData().size());
	}

	/**
	 * Remove the DirPutFile from the database. This would only be called if we had stored a ClientPut*DirMessage
	 * into the database without executing it, which never happens. 
	 * Maybe we should get rid and throw UnsupportedOperationException?
	 * @param container
	 */
	public abstract void removeFrom(ObjectContainer container);

}
