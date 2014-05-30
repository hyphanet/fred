/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- JDK imports ------------------------------------------------------------

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
        super("File exists: " + f);
        this.file = f;
    }
}
