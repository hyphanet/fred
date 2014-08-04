/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;

public abstract class ManifestPutter extends BaseClientPutter {
	
	protected ManifestPutter() {
	}

	protected ManifestPutter(short priorityClass, ClientBaseCallback cb) {
		super(priorityClass, cb);
	}

	public abstract int countFiles();
	public abstract long totalSize();
	public abstract void start(ObjectContainer container, ClientContext context) throws InsertException;
	
	public byte[] getSplitfileCryptoKey() {
		return null;
	}
	
}
