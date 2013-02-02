/**
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class DelayedFreeBucket implements Bucket {

	private final PersistentFileTracker factory;
	Bucket bucket;
	boolean freed;
	boolean removed;
	boolean reallyRemoved;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public boolean toFree() {
		return freed;
	}
	
	public boolean toRemove() {
		return removed;
	}
	
	public DelayedFreeBucket(PersistentTempBucketFactory factory, Bucket bucket) {
		this.factory = factory;
		this.bucket = bucket;
		if(bucket == null) throw new NullPointerException();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getOutputStream();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getInputStream();
	}

	@Override
	public String getName() {
		return bucket.getName();
	}

	@Override
	public long size() {
		return bucket.size();
	}

	@Override
	public boolean isReadOnly() {
		return bucket.isReadOnly();
	}

	@Override
	public void setReadOnly() {
		bucket.setReadOnly();
	}

	public Bucket getUnderlying() {
		if(freed) return null;
		return bucket;
	}
	
	@Override
	public void free() {
		synchronized(this) { // mutex on just this method; make a separate lock if necessary to lock the above
			if(freed) return;
			if(logMINOR)
				Logger.minor(this, "Freeing "+this+" underlying="+bucket, new Exception("debug"));
			this.factory.delayedFreeBucket(this);
			freed = true;
		}
	}

	@Override
	public void storeTo(ObjectContainer container) {
		bucket.storeTo(container);
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing from database: "+this);
		synchronized(this) {
			boolean wasQueued = freed || removed;
			if(!freed)
				Logger.error(this, "Asking to remove from database but not freed: "+this, new Exception("error"));
			removed = true;
			if(!wasQueued)
				this.factory.delayedFreeBucket(this);
		}
	}

	@Override
	public String toString() {
		return super.toString()+":"+bucket;
	}
	
	public void objectOnActivate(ObjectContainer container) {
//		StackTraceElement[] elements = Thread.currentThread().getStackTrace();
//		if(elements != null && elements.length > 100) {
//			System.err.println("Infinite recursion in progress...");
//		}
		if(logMINOR)
			Logger.minor(this, "Activating "+super.toString()+" : "+bucket.getClass());
		if(bucket == this) {
			Logger.error(this, "objectOnActivate on DelayedFreeBucket: wrapping self!!!");
			return;
		}
		// Cascading activation of dependancies
		container.activate(bucket, 1);
	}

	@Override
	public Bucket createShadow() {
		return bucket.createShadow();
	}

	public void realFree() {
		bucket.free();
	}

	public void realRemoveFrom(ObjectContainer container) {
		synchronized(this) {
			if(reallyRemoved)
				Logger.error(this, "Calling realRemoveFrom() twice on "+this);
			reallyRemoved = true;
		}
		bucket.removeFrom(container);
		container.delete(this);
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(reallyRemoved) {
			Logger.error(this, "objectCanNew() on "+this+" but really removed = "+reallyRemoved+" already freed="+freed+" removed="+removed, new Exception("debug"));
			return false;
		}
		assert(bucket != null);
		return true;
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		if(reallyRemoved) {
			Logger.error(this, "objectCanUpdate() on "+this+" but really removed = "+reallyRemoved+" already freed="+freed+" removed="+removed, new Exception("debug"));
			return false;
		}
		assert(bucket != null);
		return true;
	}

}