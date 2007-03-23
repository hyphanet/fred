package freenet.support.io;

import java.io.File;
import java.io.IOException;

/**
 * Thrown when a FileBucket is required to create a new file but not overwrite an existing file,
 * and the file exists.
 */
public class FileExistsException extends IOException {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public final File file;
	
	public FileExistsException(File f) {
		super("File exists: "+f);
		this.file = f;
	}

}
