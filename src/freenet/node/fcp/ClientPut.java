/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.async.BinaryBlob;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutter;
import freenet.clients.fcp.ClientRequest;
import freenet.clients.fcp.IdentifierCollisionException;
import freenet.clients.fcp.NotAllowedException;
import freenet.clients.fcp.PersistentRequestClient;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class ClientPut extends ClientPutBase {

	ClientPutter putter;
	private final short uploadFrom;
	/** Original filename if from disk, otherwise null. Purely for PersistentPut. */
	private final File origFilename;
	/** If uploadFrom==UPLOAD_FROM_REDIRECT, this is the target of the redirect */
	private final FreenetURI targetURI;
	private Bucket data;
	private final ClientMetadata clientMetadata;
	/** We store the size of inserted data before freeing it */
	private long finishedSize;
	/** Filename if the file has one */
	private final String targetFilename;
	/** If true, we are inserting a binary blob: No metadata, no URI is generated. */
	private final boolean binaryBlob;
	private transient boolean compressing;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	protected ClientPut() {
	    throw new UnsupportedOperationException();
	}

	@Override
	protected void freeData(ObjectContainer container) {
		Bucket d;
		synchronized(this) {
			d = data;
			data = null;
			if(d == null) return;
			if(persistenceType == PERSIST_FOREVER)
				container.activate(d, 5);
			finishedSize = d.size();
		}
		d.free();
	}
	
	@Override
	protected freenet.client.async.ClientRequester getClientRequest() {
		return putter;
	}

	@Override
	protected String getTypeName() {
		return "PUT";
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	public FreenetURI getFinalURI(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER)
			container.activate(generatedURI, 5);
		return generatedURI;
	}
	
    public static final short UPLOAD_FROM_DIRECT = 0;
    public static final short UPLOAD_FROM_DISK = 1;
    public static final short UPLOAD_FROM_REDIRECT = 2;
    
	public boolean isDirect() {
		return uploadFrom == UPLOAD_FROM_DIRECT;
	}

	public File getOrigFilename(ObjectContainer container) {
		if(uploadFrom != UPLOAD_FROM_DISK)
			return null;
		if(persistenceType == PERSIST_FOREVER)
			container.activate(origFilename, 5);
		return origFilename;
	}

	public long getDataSize(ObjectContainer container) {
		if(data == null)
			return finishedSize;
		else {
			if(persistenceType == PERSIST_FOREVER) container.activate(data, 1);
			return data.size();
		}
	}

	public String getMIMEType() {
		return clientMetadata.getMIMEType();
	}

	public enum COMPRESS_STATE {
		/** Waiting for a slot on the compression scheduler */
		WAITING,
		/** Compressing the data */
		COMPRESSING,
		/** Inserting the data */
		WORKING
	}
	
	/** Probably not meaningful for ClientPutDir's */
	public COMPRESS_STATE isCompressing(ObjectContainer container) {
		if(persistenceType == PERSIST_FOREVER) container.activate(ctx, 1);
		if(ctx.dontCompress) return COMPRESS_STATE.WORKING;
		synchronized(this) {
			if(progressMessage == null) return COMPRESS_STATE.WAITING; // An insert starts at compressing
			// The progress message persists... so we need to know whether we have
			// started compressing *SINCE RESTART*.
			if(compressing) return COMPRESS_STATE.COMPRESSING;
			return COMPRESS_STATE.WORKING;
		}
	}

    @Override
    public ClientRequest migrate(PersistentRequestClient newClient, ObjectContainer container,
            NodeClientCore core) throws IdentifierCollisionException, NotAllowedException,
            IOException {
        // FIXME
        Logger.error(this, "Not migrating upload");
        return null;
    }

}
