/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.config.InvalidConfigValueException;
import freenet.l10n.NodeL10n;
import freenet.support.api.StringCallback;

import java.util.HashSet;
import java.io.File;
import java.io.IOException;

/**
** Represents a program directory, and keeps track of the files that freenet
** stores there.
**
** @author infinity0
** @see <a href=http://new-wiki.freenetproject.org/Program_files>New wiki program files documentation</a>
** @see <a href=http://wiki.freenetproject.org/Program_files>Old wiki program files documentation</a>
*/
public class ProgramDirectory {

	/** Directory path */
	protected File dir = null;
	/** Keeps track of all the files saved in this directory */
	final protected HashSet<String> files = new HashSet<String>();
	final private StringCallback callback;
	final private String moveErrMsg;

	private static int sortOrder = 0;
	protected static synchronized int nextOrder() {
		return sortOrder++;
	}

	public ProgramDirectory() {
		this(null);
	}

	public ProgramDirectory(String moveErrMsg) {
		this.moveErrMsg = moveErrMsg;
		this.callback = (moveErrMsg != null)? new RWDirectoryCallback(): new DirectoryCallback();
	}

	/**
	** Move the directory. Currently not implemented, except in the
	** initialisation case.
	*/
	public void move(String file) throws IOException {
		File dir = new File(file);
		if (this.dir != null && !dir.equals(this.dir)) { throw new IOException("move not implemented"); }

		if (!((dir.exists() && dir.isDirectory()) || (dir.mkdir()))) {
			throw new IOException("Could not find or make a directory called: " + l10n(file));
		}

		this.dir = dir;
	}

	public StringCallback getStringCallback() {
		return callback;
	}

	public class DirectoryCallback extends StringCallback {
		@Override
		public String get() {
			return dir.getPath();
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			if (dir == null) { dir = new File(val); return; }
			if (dir.equals(new File(val))) return;
			// FIXME support it
			// Don't need to translate the below as very few users will use it.
			throw new InvalidConfigValueException("Moving program directory on the fly not supported at present");
		}

		@Override
		public boolean isReadOnly() {
			return true;
		}
	}

	public class RWDirectoryCallback extends DirectoryCallback {
		@Override
		public void set(String val) throws InvalidConfigValueException {
			if (dir == null) { dir = new File(val); return; }
			if (dir.equals(new File(val))) return;
			File f = new File(val);
			if(!((f.exists() && f.isDirectory()) || (f.mkdir())))
				// Relatively commonly used, despite being advanced (i.e. not something we want to show to newbies). So translate it.
				throw new InvalidConfigValueException(l10n(moveErrMsg));
			dir = new File(val);
		}

		@Override
		public boolean isReadOnly() {
			return false;
		}
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

	private static String l10n(String key) {
		return NodeL10n.getBase().getString(key);
	}

}
