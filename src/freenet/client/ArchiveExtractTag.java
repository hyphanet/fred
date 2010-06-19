package freenet.client;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

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
