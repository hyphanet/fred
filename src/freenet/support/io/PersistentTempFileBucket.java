package freenet.support.io;

import java.io.File;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class PersistentTempFileBucket extends TempFileBucket {

	public PersistentTempFileBucket(long id, FilenameGenerator generator) {
		this(id, generator, true);
	}
	
	protected PersistentTempFileBucket(long id, FilenameGenerator generator, boolean deleteOnFree) {
		super(id, generator, deleteOnFree);
	}

	@Override
	protected boolean deleteOnFinalize() {
		// Do not delete on finalize
		return false;
	}
	
	@Override
	protected boolean deleteOnExit() {
		// DO NOT DELETE ON EXIT !!!!
		return false;
	}
	
	public static Bucket create(SimpleFieldSet fs, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		String tmp = fs.get("Filename");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No filename");
		File file = FileUtil.getCanonicalFile(new File(tmp));
		long id = f.getID(file);
		if(id == -1)
			throw new CannotCreateFromFieldSetException("Cannot derive persistent temp file id from filename "+file);
		tmp = fs.get("Length");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No length");
		long length;
		try {
			length = Long.parseLong(tmp);
			if(length !=  file.length())
				throw new CannotCreateFromFieldSetException("Invalid length: should be "+length+" actually "+file.length()+" on "+file);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt length "+tmp, e);
		}
		Bucket bucket = new PersistentTempFileBucket(id, f.getGenerator(), true);
		if(file.exists()) // no point otherwise!
			f.register(file);
		return bucket;
	}
	
	@Override
	public SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize()) return null;
		SimpleFieldSet fs = super.toFieldSet();
		fs.putOverwrite("Type", "PersistentTempFileBucket");
		fs.put("FilenameID", filenameID);
		return fs;
	}
	
	/** Must override createShadow() so it creates a persistent bucket, which will have
	 * deleteOnExit() = deleteOnFinalize() = false.
	 */
	@Override
	public Bucket createShadow() throws IOException {
		PersistentTempFileBucket ret = new PersistentTempFileBucket(filenameID, generator, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
	
}
