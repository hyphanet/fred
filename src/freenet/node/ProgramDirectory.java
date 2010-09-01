/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.config.InvalidConfigValueException;
import freenet.support.api.StringCallback;

import java.util.HashSet;
import java.io.File;
import java.io.IOException;

/**
** Represents a program directory, and keeps track of the files that freenet
** stores there.
**
** @author infinity0
** @see http://new-wiki.freenetproject.org/Program_files
** @see http://wiki.freenetproject.org/Program_files
*/
public class ProgramDirectory {

	/** Directory path */
	protected File dir = null;
	/** Keeps track of all the files saved in this directory */
	final protected HashSet<String> files = new HashSet<String>();

	public ProgramDirectory() {
		// pass
	}

	/**
	** Move the directory. Currently not implemented, except in the
	** initialisation case.
	*/
	public void move(String file) throws IOException {
		if (this.dir != null) { throw new IOException("move not implemented"); }

		File dir = new File(file);
		if (!((dir.exists() && dir.isDirectory()) || (dir.mkdir()))) {
			throw new IOException("Could not find or make a directory called: " + file);
		}

		this.dir = dir.getCanonicalFile();
	}

	public StringCallback getStringCallback() {
		return new StringCallback() {
			@Override
			public String get() {
				return dir.getPath();
			}

			@Override
			public void set(String val) throws InvalidConfigValueException {
				if (dir.equals(new File(val))) return;
				// FIXME support it
				// Don't need to translate the below as very few users will use it.
				throw new InvalidConfigValueException("Moving program directory on the fly not supported at present");
			}

			@Override
			public boolean isReadOnly() {
				return true;
			}
		};
	}

	/**
	** Return a {@link File} object from the given string basename.
	*/
	public File file(String base) {
		files.add(base);
		return new File(dir, base);
	}

	public File dir() {
		return dir;
	}

}
