package freenet.client.async;

import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;
import freenet.support.ListUtils;
import freenet.support.Logger;

/**
 * Just as with SectoredRandomGrabArray, activation is a big deal, and we can
 * safely assume that == <=> equals(). So we use a vector, and hope it doesn't
 * get too big (it won't in the near future). Any structure that might conceivably
 * call equals() is doomed, because either it requires activation (extra disk 
 * seek), or it will cause NPEs or messy code to avoid them. One option if size
 * becomes a problem is to have individual objects in the database for each
 * SendableRequest; this might involve many disk seeks, so is bad.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PersistentSendableRequestSet implements SendableRequestSet {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(PlainManifestPutter.class);
	}

	private final ArrayList<SendableRequest> list;
	
	PersistentSendableRequestSet() {
		list = new ArrayList<freenet.node.SendableRequest>();
	}
	
	@Override
	public synchronized boolean addRequest(SendableRequest req, ObjectContainer container) {
		container.activate(list, 1);
		int idx = find(req, container);
		if(idx == -1) {
			list.add(req);
			container.store(req);
			/** Store to depth 1, otherwise it will update to depth 3 */
			container.ext().store(list, 1);
			return true;
		} else return false;
	}

	private synchronized int find(SendableRequest req, ObjectContainer container) {
		for(int i=0;i<list.size();i++) {
			if(list.get(i) == req) return i;
		}
		if(container.ext().isStored(req)) {
			long id = container.ext().getID(req);
			for(int i=0;i<list.size();i++) {
				if(container.ext().getID(list.get(i)) == id) {
					Logger.warning(this, "db4o being wierd: found on second round");
					return i;
				}
			}
		}
		return -1;
	}

	@Override
	public synchronized SendableRequest[] listRequests(ObjectContainer container) {
		container.activate(list, 1);
		return list.toArray(new SendableRequest[list.size()]);
	}

	@Override
	public synchronized boolean removeRequest(SendableRequest req, ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Removing "+req+" from "+this);
		container.activate(list, 1);
		boolean success = false;
		while(true) {
			int idx = find(req, container);
			if(idx == -1) break;
			if(success)
				Logger.error(this, "Request is in "+this+" twice or more : "+req);
			success = true;
			ListUtils.removeBySwapLast(list,idx);
		}
		if(!success) return false;
		container.ext().store(list, 1);
		return success;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(list, 1);
		container.delete(list);
		container.delete(this);
	}

}
