/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 *
 * @author unknown
 */
public class SerializableToFieldSetBucketUtil {

	// FIXME use something other than ResumeException???
	/**
	 * 
	 * @param fs
	 * @param random
	 * @param f
	 * @return
	 * @throws CannotCreateFromFieldSetException
	 */
	public static Bucket create(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		if(fs == null) {
			if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class)) {
				Logger.minor(SerializableToFieldSetBucketUtil.class, "fs = null", new Exception("debug"));
			}
			return null;
		}
		String type = fs.get("Type");
		if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class)) {
			Logger.minor(SerializableToFieldSetBucketUtil.class, "Creating: " + type);
		}
		if(type == null) {
			throw new CannotCreateFromFieldSetException("No type");
		} else if(type.equals("FileBucket")) {
			return BaseFileBucket.create(fs, f);
		} else if(type.equals("PaddedEphemerallyEncryptedBucket")) {
			return new PaddedEphemerallyEncryptedBucket(fs, random, f);
		} else if(type.equals("NullBucket")) {
			return new NullBucket();
		} else if(type.equals("ReadOnlyFileSliceBucket")) {
			return new ReadOnlyFileSliceBucket(fs);
		} else if(type.equals("DelayedFreeBucket")) {
			return new DelayedFreeBucket(fs, random, f);
		} else if(type.equals("PersistentTempFileBucket")) {
			return PersistentTempFileBucket.create(fs, f);
		} else {
			throw new CannotCreateFromFieldSetException("Unrecognized type " + type);
		}
	}

}
