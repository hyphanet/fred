package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 *
 */
public class NullClientCallback implements ClientGetCallback, ClientPutCallback {
    private static volatile boolean logDEBUG;

    static {
		Logger.registerClass(NullClientCallback.class);
    }

	@Override
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFailure e=" + e + ", state=" + state + ", container=" + container, e);
	}

	@Override
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFailure e=" + e + ", state=" + state + ", container=" + container, e);
	}

	@Override
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFetchable state=" + state + ", container=" + container);
	}

	@Override
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onGeneratedURI uri=" + uri + ", state=" + state + ", container=" + container);
	}

	@Override
	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	@Override
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onSuccess result=" + result + ", state=" + state + ", container=" + container);
		result.data.free();
	}

	@Override
	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onSuccess state=" + state + ", container=" + container);
	}

	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state,
			ObjectContainer container) {
		if(logDEBUG) Logger.debug(this, "NullClientCallback#onGeneratedMetadata state=" + state);
		metadata.free();
	}

}
