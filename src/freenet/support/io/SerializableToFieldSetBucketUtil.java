/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class SerializableToFieldSetBucketUtil {

	// FIXME use something other than ResumeException???
	
	public static Bucket create(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		if(fs == null) {
			if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class))
				Logger.minor(SerializableToFieldSetBucketUtil.class, "fs = null", new Exception("debug"));
			return null;
		}
		String type = fs.get("Type");
		if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class))
			Logger.minor(SerializableToFieldSetBucketUtil.class, "Creating: "+type);
		if(type == null) {
			throw new CannotCreateFromFieldSetException("No type");
		} else if("FileBucket".equals(type)) {
			return BaseFileBucket.create(fs, f);
		} else if("PaddedEphemerallyEncryptedBucket".equals(type)) {
			return new PaddedEphemerallyEncryptedBucket(fs, random, f);
		} else if("NullBucket".equals(type)) {
			return new NullBucket();
		} else if("ReadOnlyFileSliceBucket".equals(type)) {
			return new ReadOnlyFileSliceBucket(fs);
		} else if("DelayedFreeBucket".equals(type)) {
			return new DelayedFreeBucket(fs, random, f);
		} else if("PersistentTempFileBucket".equals(type)) {
			return PersistentTempFileBucket.create(fs, f);
		} else
			throw new CannotCreateFromFieldSetException("Unrecognized type "+type);
	}

}
