/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class BaseClientPutter extends ClientRequester {

    private static final long serialVersionUID = 1L;

    /**
	 * zero arg c'tor for db4o on jamvm
	 */
	protected BaseClientPutter() {
	}

	protected BaseClientPutter(short priorityClass, ClientBaseCallback cb) {
		super(priorityClass, cb);
	}

	public abstract void onMajorProgress(ObjectContainer container);

	public void dump(ObjectContainer container) {
		// Do nothing
	}

	public abstract void onTransition(ClientPutState from, ClientPutState to);

	public abstract int getMinSuccessFetchBlocks();
}
