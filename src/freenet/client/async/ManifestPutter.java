/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.node.RequestClient;

public abstract class ManifestPutter extends BaseClientPutter {
	
	protected ManifestPutter() {
	}

	protected ManifestPutter(short priorityClass, RequestClient context) {
		super(priorityClass, context);
	}

	public abstract int countFiles();
	public abstract long totalSize();
	public abstract void start(ObjectContainer container, ClientContext context) throws InsertException;
	public void removeFrom(ObjectContainer container, ClientContext context) {
		super.removeFrom(container, context);
	}
	
	public byte[] getSplitfileCryptoKey() {
		return null;
	}
	
	public static final short MANIFEST_SIMPLEPUTTER = 0;
	public static final short MANIFEST_DEFAULTPUTTER = 1;
	
	public static String manifestPutterTypeString(short type) {
		switch(type) {
		case MANIFEST_SIMPLEPUTTER:
			return "Simple";
		case MANIFEST_DEFAULTPUTTER:
			return "Default";
		default:
			return Short.toString(type);
		}
	}
	
}
