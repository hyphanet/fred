/**
 * 
 */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;

public class NullClientCallback implements ClientCallback {
	
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		// Ignore
	}

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		// Impossible
	}

	public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		// Impossible
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		// Impossible
	}

	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		result.data.free();
	}

	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		// Impossible
	}
	
}