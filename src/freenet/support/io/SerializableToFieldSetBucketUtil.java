/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.IOException;

import freenet.crypt.RandomSource;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class SerializableToFieldSetBucketUtil {

	// FIXME use something other than ResumeException???
	
	public static Bucket create(SimpleFieldSet fs, RandomSource random, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		if(fs == null) {
			if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class))
				Logger.minor(SerializableToFieldSetBucketUtil.class, "fs = null");
			return null;
		}
		String type = fs.get("Type");
		if(Logger.shouldLog(Logger.MINOR, SerializableToFieldSetBucketUtil.class))
			Logger.minor(SerializableToFieldSetBucketUtil.class, "Creating: "+type);
		if(type == null) {
			if(fs.get("DecryptKey") != null && fs.get("Filename") != null) {
				String filename = fs.get("Filename");
				byte[] decryptKey = HexUtil.hexToBytes(fs.get("DecryptKey"));
				long len = -1;
				if(fs.get("Size") != null) {
					try {
						len = Long.parseLong(fs.get("Size"));
					} catch (NumberFormatException e) {
						throw new CannotCreateFromFieldSetException("Corrupt dataLength: "+fs.get("Size"), e);
					}
				}
				File fnam = new File(filename);
				if(!fnam.exists()) {
					File persistent = new File(f.getDir(), filename);
					if(persistent.exists()) fnam = persistent;
				}
				f.register(fnam);
				FileBucket fb = new FileBucket(fnam, false, false, false, true);
				try {
					PaddedEphemerallyEncryptedBucket eb = 
						new PaddedEphemerallyEncryptedBucket(fb, 1024, len, decryptKey, random);
					return eb;
				} catch (IOException e) {
					throw new CannotCreateFromFieldSetException("Cannot create from old-format fieldset: "+e, e);
				}
			}
			
			throw new CannotCreateFromFieldSetException("No type");
		} else if(type.equals("FileBucket")) {
			return new FileBucket(fs, f);
		} else if(type.equals("PaddedEphemerallyEncryptedBucket")) {
			return new PaddedEphemerallyEncryptedBucket(fs, random, f);
		} else if(type.equals("NullBucket")) {
			return new NullBucket();
		} else if(type.equals("RandomAccessFileBucket")) {
			return new RandomAccessFileBucket(fs, f);
		} else if(type.equals("ReadOnlyFileSliceBucket")) {
			return new ReadOnlyFileSliceBucket(fs);
		} else if(type.equals("DelayedFreeBucket")) {
			return new DelayedFreeBucket(fs, random, f);
		} else
			throw new CannotCreateFromFieldSetException("Unrecognized type "+type);
	}

}
