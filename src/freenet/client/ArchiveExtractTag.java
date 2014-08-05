package freenet.client;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.Bucket;

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

	public boolean checkBroken(ClientContext context) {
		if(data == null || actx == null || callback == null || handler == null) {
			String error = "Archive extract tag is broken: data="+data+" actx="+actx+" callback="+callback+" handler="+handler;
			Logger.error(this, error);
			if(callback != null) {
				callback.onFailed(new ArchiveFailureException(error), context);
			}
			if(data != null && freeBucket) {
				data.free();
			}
			// Don't remove actx, not our problem
			// Ditto callback
			// Ditto handler
			return true;
		}
		return false;
	}
	
}
