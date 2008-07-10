package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
import freenet.support.HexUtil;

public class PendingKeyItem {
	
	final long nodeDBHandle;
	final Key key;
	/**
	 * EVIL DB4O HACK:
	 * Db4o does not support indexing objects with a Comparator. It will only
	 * index by the object id. It will not index by a byte[]. But it WILL index
	 * by a string quite happily and very fast. So we convert to a string here.
	 * Not doing so results in db4o instantiating every key in order to compare
	 * it... whereas doing so results in a fast index lookup.
	 */
	final String fullKeyAsBytes;
	private SendableGet[] getters;
	
	PendingKeyItem(Key key, SendableGet getter, long nodeDBHandle) {
		this.key = key;
		this.getters = new SendableGet[] { getter };
		this.nodeDBHandle = nodeDBHandle;
		this.fullKeyAsBytes = HexUtil.bytesToHex(key.getFullKey());
	}
	
	public void addGetter(SendableGet getter) {
		for(int i=0;i<getters.length;i++) {
			if(getters[i] == getter) return;
		}
		SendableGet[] newGetters = new SendableGet[getters.length+1];
		System.arraycopy(getters, 0, newGetters, 0, getters.length);
		newGetters[getters.length] = getter;
		getters = newGetters;
	}
	
	/**
	 * @param getter
	 * @return True if the getter was removed. Caller should check isEmpty() afterwards.
	 */
	public boolean removeGetter(SendableGet getter) {
		int found = 0;
		for(int i=0;i<getters.length;i++) {
			if(getters[i] == getter) found++;
		}
		if(found == 0) return false;
		if(found == getters.length)
			getters = new SendableGet[0];
		else {
			SendableGet[] newGetters = new SendableGet[getters.length - found];
			int x = 0;
			for(int i=0;i<getters.length;i++) {
				if(getters[i] == getter) continue;
				newGetters[x++] = getters[i];
			}
			getters = newGetters;
		}
		return true;
	}
	
	public boolean isEmpty() {
		return getters.length == 0;
	}

	public boolean hasGetter(SendableRequest req) {
		for(int i=0;i<getters.length;i++)
			if(getters[i] == req) return true;
		return false;
	}

	public SendableGet[] getters() {
		return getters;
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(key, 5);
	}

}