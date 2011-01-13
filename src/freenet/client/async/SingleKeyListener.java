package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.LowLevelGetException;
import freenet.node.SendableGet;
import freenet.support.Logger;

public class SingleKeyListener implements KeyListener {
	
	private final Key key;
	private final BaseSingleFileFetcher fetcher;
	private boolean done;
	private short prio;
	private final boolean persistent;
	private final boolean realTime;

	public SingleKeyListener(Key key, BaseSingleFileFetcher fetcher, short prio, boolean persistent, boolean realTime) {
		this.key = key;
		this.fetcher = fetcher;
		this.prio = prio;
		this.persistent = persistent;
		this.realTime = realTime;
	}

	public long countKeys() {
		if(done) return 0;
		else return 1;
	}

	public short definitelyWantKey(Key key, byte[] saltedKey, ObjectContainer container,
			ClientContext context) {
		if(!key.equals(this.key)) return -1;
		else return prio;
	}

	public HasKeyListener getHasKeyListener() {
		return fetcher;
	}

	public short getPriorityClass(ObjectContainer container) {
		return prio;
	}

	public SendableGet[] getRequestsForKey(Key key, byte[] saltedKey, ObjectContainer container,
			ClientContext context) {
		if(!key.equals(this.key)) return null;
		return new SendableGet[] { fetcher };
	}

	public boolean handleBlock(Key key, byte[] saltedKey, KeyBlock found,
			ObjectContainer container, ClientContext context) {
		if(!key.equals(this.key)) return false;
		if(persistent)
			container.activate(fetcher, 1);
		try {
			fetcher.onGotKey(key, found, container, context);
		} catch (Throwable t) {
			Logger.error(this, "Failed: "+t, t);
			fetcher.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), null, container, context);
		}
		if(persistent)
			container.deactivate(fetcher, 1);
		synchronized(this) {
			done = true;
		}
		return true;
	}

	public Key[] listKeys(ObjectContainer container) {
		return new Key[] { key };
	}

	public boolean persistent() {
		return persistent;
	}

	public boolean probablyWantKey(Key key, byte[] saltedKey) {
		if(done) return false;
		return key.equals(this.key);
	}

	public synchronized void onRemove() {
		done = true;
	}

	public boolean isEmpty() {
		return done;
	}

	public boolean isSSK() {
		return key instanceof NodeSSK;
	}

	public boolean isRealTime() {
		return realTime;
	}

}
