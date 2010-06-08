/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.RequestClient;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class BaseClientPutter extends ClientRequester {

	protected BaseClientPutter(short priorityClass, RequestClient context) {
		super(priorityClass, context);
	}

	public abstract void onMajorProgress(ObjectContainer container);

	public void dump(ObjectContainer container) {
		// Do nothing
	}

	public abstract void onTransition(ClientPutState from, ClientPutState to, ObjectContainer container);

}
