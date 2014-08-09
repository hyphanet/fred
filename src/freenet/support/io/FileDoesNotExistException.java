package freenet.support.io;

import java.io.File;
import java.io.IOException;

/** Thrown when a temp file disappears before we try to use it. */
public class FileDoesNotExistException extends IOException {
    
    private static final long serialVersionUID = 1L;
    final File file;

    public FileDoesNotExistException(File f) {
        super("File does not exist: "+f);
        this.file = f;
    }

}
