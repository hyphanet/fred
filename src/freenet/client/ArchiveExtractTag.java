package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.Bucket;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class ArchiveExtractTag {
	
	final ArchiveHandlerImpl handler;
	final Bucket data;
	final boolean freeBucket;
	final ArchiveContext actx;
	final String element;
	final ArchiveExtractCallback callback;
	final long nodeDBHandle;
	
	ArchiveExtractTag(ArchiveHandlerImpl handler, Bucket data, boolean freeBucket, ArchiveContext actx, String element, ArchiveExtractCallback callback, long nodeDBHandle) {
		if(handler == null) throw new NullPointerException();
		this.handler = handler;
		this.data = data;
		this.freeBucket = freeBucket;
		this.actx = actx;
		this.element = element;
		this.callback = callback;
		this.nodeDBHandle = nodeDBHandle;
	}

	public void activateForExecution(ObjectContainer container) {
		container.activate(this, 1);
		container.activate(data, 5);
		handler.activateForExecution(container);
		container.activate(actx, 5);
		container.activate(callback, 1);
	}

	public boolean checkBroken(ObjectContainer container, ClientContext context) {
		container.activate(this, 1);
		if(data == null || actx == null || callback == null || handler == null) {
			String error = "Archive extract tag is broken: data="+data+" actx="+actx+" callback="+callback+" handler="+handler;
			Logger.error(this, error);
			if(callback != null) {
				container.activate(callback, 1);
				callback.onFailed(new ArchiveFailureException(error), container, context);
			}
			if(data != null && freeBucket) {
				container.activate(data, 1);
				data.free();
				data.removeFrom(container);
			}
			// Don't remove actx, not our problem
			// Ditto callback
			// Ditto handler
			return true;
		}
		return false;
	}
	
}
