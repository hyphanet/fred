package freenet.support.io;

import java.io.File;

public class NullPersistentFileTracker implements PersistentFileTracker {

	public void register(File file) {
		// Do nothing
	}

}
