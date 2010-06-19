package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientPutCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 *
 */
public class NullClientCallback implements ClientGetCallback, ClientPutCallback {
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;

    static {
		Logger.registerClass(NullClientCallback.class);
    }

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFailure e=" + e + ", state=" + state + ", container=" + container, e);
	}

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFailure e=" + e + ", state=" + state + ", container=" + container, e);
	}

	public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onFetchable state=" + state + ", container=" + container);
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onGeneratedURI uri=" + uri + ", state=" + state + ", container=" + container);
	}

	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onSuccess result=" + result + ", state=" + state + ", container=" + container);
		result.data.free();
	}

	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		if (logDEBUG) Logger.debug(this, "NullClientCallback#onSuccess state=" + state + ", container=" + container);
	}

}
