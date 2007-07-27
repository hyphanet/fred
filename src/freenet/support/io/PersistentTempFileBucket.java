package freenet.support.io;

import java.io.File;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class PersistentTempFileBucket extends TempFileBucket {

	protected PersistentTempFileBucket(long id, FilenameGenerator generator) {
		super(id, generator);
	}

	protected boolean deleteOnFinalize() {
		// Do not delete on finalize
		return false;
	}
	
	protected boolean deleteOnExit() {
		// DO NOT DELETE ON EXIT !!!!
		return false;
	}
	
	public static Bucket create(SimpleFieldSet fs, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		String tmp = fs.get("Filename");
		if(tmp == null) throw new CannotCreateFromFieldSetException("No filename");
		File file = FileUtil.getCanonicalFile(new File(tmp));
		long id = f.getID(file);
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
		Bucket bucket = new PersistentTempFileBucket(id, f.getGenerator());
		if(file.exists()) // no point otherwise!
			f.register(file);
		return bucket;
	}
	
	public SimpleFieldSet toFieldSet() {
		if(deleteOnFinalize()) return null;
		SimpleFieldSet fs = super.toFieldSet();
		fs.putOverwrite("Type", "PersistentTempFileBucket");
		return fs;
	}
	
}
