package freenet.client;

import com.db4o.ObjectContainer;

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
	
}
