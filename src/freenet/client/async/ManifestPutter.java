/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.node.RequestClient;
import java.io.Serializable;

import freenet.client.InsertException;

public abstract class ManifestPutter extends BaseClientPutter {
	
    private static final long serialVersionUID = 1L;

	/** Required because {@link Serializable} is implemented by a parent class. */
	protected ManifestPutter() {
	}

	protected ManifestPutter(short priorityClass, RequestClient requestClient) {
		super(priorityClass, requestClient);
	}

	public abstract int countFiles();
	public abstract long totalSize();
	public abstract void start(ClientContext context) throws InsertException;
	
	public byte[] getSplitfileCryptoKey() {
		return null;
	}
	
}
