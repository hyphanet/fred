/**
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class DelayedFreeBucket implements Bucket, SerializableToFieldSetBucket {

	private final PersistentFileTracker factory;
	Bucket bucket;
	boolean freed;
	
	public DelayedFreeBucket(PersistentTempBucketFactory factory, PaddedEphemerallyEncryptedBucket bucket) {
		this.factory = factory;
		this.bucket = bucket;
	}

	public DelayedFreeBucket(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		factory = f;
		freed = false;
		bucket = SerializableToFieldSetBucketUtil.create(fs, random, f);
	}

	public OutputStream getOutputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getOutputStream();
	}

	public InputStream getInputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		return bucket.getInputStream();
	}

	public String getName() {
		return bucket.getName();
	}

	public long size() {
		return bucket.size();
	}

	public boolean isReadOnly() {
		return bucket.isReadOnly();
	}

	public void setReadOnly() {
		bucket.setReadOnly();
	}

	public void free() {
		synchronized(this) { // mutex on just this method; make a separate lock if necessary to lock the above
			if(freed) return;
			this.factory.delayedFreeBucket(bucket);
			freed = true;
		}
	}

	public SimpleFieldSet toFieldSet() {
		if(freed) return null;
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Type", "DelayedFreeBucket");
		if(bucket instanceof SerializableToFieldSetBucket) {
			fs.put("Underlying", ((SerializableToFieldSetBucket)bucket).toFieldSet());
		} else {
			Logger.error(this, "Cannot serialize underlying bucket: "+bucket);
			return null;
		}
		return fs;
	}

}