package freenet.support.io;

import freenet.crypt.RandomSource;
import freenet.support.SimpleFieldSet;

public class SerializableToFieldSetBucketUtil {

	// FIXME use something other than ResumeException???
	
	public static Bucket create(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		if(fs == null) return null;
		String type = fs.get("Type");
		if(type == null) {
			throw new CannotCreateFromFieldSetException("No type");
		} else if(type.equals("FileBucket")) {
			return new FileBucket(fs, f);
		} else if(type.equals("PaddedEphemerallyEncryptedBucket")) {
			return new PaddedEphemerallyEncryptedBucket(fs, random, f);
		} else if(type.equals("NullBucket")) {
			return new NullBucket();
		} else if(type.equals("RandomAccessFileBucket")) {
			return new RandomAccessFileBucket(fs, f);
		} else
			throw new CannotCreateFromFieldSetException("Unrecognized type "+type);
	}

}
