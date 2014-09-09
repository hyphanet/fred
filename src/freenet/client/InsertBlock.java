/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.Serializable;

import freenet.keys.FreenetURI;
import freenet.support.api.RandomAccessBucket;

/**
 * Class to contain everything needed for an insert.
 * 
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * losing uploads.
 */
public class InsertBlock implements Serializable {

    private static final long serialVersionUID = 1L;
    private RandomAccessBucket data;
	private boolean isFreed;
	public FreenetURI desiredURI;
	public ClientMetadata clientMetadata;
	
	public InsertBlock(RandomAccessBucket data, ClientMetadata metadata, FreenetURI desiredURI) {
		if(data == null) throw new NullPointerException();
		this.data = data;
		this.isFreed = false;
		if(metadata == null)
			clientMetadata = new ClientMetadata();
		else
			clientMetadata = metadata;
		this.desiredURI = desiredURI;
	}
	
	public RandomAccessBucket getData() {
		return (isFreed ? null : data);
	}
	
	public void free(){
		synchronized (this) {
			if(isFreed) return;
			isFreed = true;
			if(data == null) return;
		}
		data.free();
	}
	
	/** Null out the data so it doesn't get removed in removeFrom().
	 * Call this when the data becomes somebody else's problem. */
	public void nullData() {
		data = null;
	}

	/** Null out the URI so it doesn't get removed in removeFrom().
	 * Call this when the URI becomes somebody else's problem. */
	public void nullURI() {
		this.desiredURI = null;
	}

	public void nullMetadata() {
		this.clientMetadata = null;
	}
	
}
