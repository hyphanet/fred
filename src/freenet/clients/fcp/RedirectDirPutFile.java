package freenet.clients.fcp;

import java.net.MalformedURLException;

import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.ManifestElement;
import freenet.support.api.RandomAccessBucket;

public class RedirectDirPutFile extends DirPutFile {

	final FreenetURI targetURI;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public static RedirectDirPutFile create(String name, String contentTypeOverride, SimpleFieldSet subset, 
			String identifier, boolean global) throws MessageInvalidException {
		String target = subset.get("TargetURI");
		if(target == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier, global);
		FreenetURI targetURI;
		try {
			targetURI = new FreenetURI(target);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier, global);
		}
        if(logMINOR)
        	Logger.minor(RedirectDirPutFile.class, "targetURI = "+targetURI);
        String mimeType;
        if(contentTypeOverride != null)
        	mimeType = contentTypeOverride;
        else
        	mimeType = guessMIME(name);
        return new RedirectDirPutFile(name, mimeType, targetURI);
	}
	
	public RedirectDirPutFile(String name, String mimeType, FreenetURI targetURI) {
		super(name, mimeType);
		this.targetURI = targetURI;
	}

	@Override
	public RandomAccessBucket getData() {
		return null;
	}

	@Override
	public ManifestElement getElement() {
		return new ManifestElement(name, targetURI, getMIMEType());
	}

}
