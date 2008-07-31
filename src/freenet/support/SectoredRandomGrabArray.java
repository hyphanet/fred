package freenet.support;

import java.util.HashMap;
import java.util.Map;

import com.db4o.ObjectContainer;
import com.db4o.types.Db4oMap;

import freenet.client.async.ClientContext;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned.
 */
public class SectoredRandomGrabArray implements RemoveRandom {

	private final Map grabArraysByClient;
	private RemoveRandomWithObject[] grabArrays;
	private final boolean persistent;
	
	public SectoredRandomGrabArray(boolean persistent, ObjectContainer container) {
		this.persistent = persistent;
		if(persistent) {
			// FIXME is this too heavyweight? Maybe we should iterate the array or something?
			grabArraysByClient = container.ext().collections().newHashMap(10);
			((Db4oMap)grabArraysByClient).activationDepth(1); // FIXME can we get away with 1??
		} else
			grabArraysByClient = new HashMap();
		grabArrays = new RemoveRandomWithObject[0];
	}

	/**
	 * Add directly to a RandomGrabArrayWithClient under us. */
	public synchronized void add(Object client, RandomGrabArrayItem item, ObjectContainer container) {
		if(item.persistent() != persistent) throw new IllegalArgumentException("item.persistent()="+item.persistent()+" but array.persistent="+persistent+" item="+item+" array="+this);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		RandomGrabArrayWithClient rga;
		if(!grabArraysByClient.containsKey(client)) {
			if(logMINOR)
				Logger.minor(this, "Adding new RGAWithClient for "+client+" on "+this+" for "+item);
			rga = new RandomGrabArrayWithClient(client, persistent, container);
			RemoveRandomWithObject[] newArrays = new RemoveRandomWithObject[grabArrays.length+1];
			System.arraycopy(grabArrays, 0, newArrays, 0, grabArrays.length);
			newArrays[grabArrays.length] = rga;
			grabArrays = newArrays;
			grabArraysByClient.put(client, rga);
			if(persistent) {
				container.set(rga);
				container.set(grabArraysByClient);
				container.set(this);
			}
		} else {
			rga = (RandomGrabArrayWithClient) grabArraysByClient.get(client);
		}
		if(logMINOR)
			Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
		// rga is auto-activated to depth 1...
		rga.add(item, container);
		if(persistent)
			// now deactivate to save memory
			container.deactivate(rga, 1);
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	}

	/**
	 * Get a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized RemoveRandomWithObject getGrabber(Object client) {
		return (RemoveRandomWithObject) grabArraysByClient.get(client); // auto-activated to depth 1
	}

	/**
	 * Put a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized void addGrabber(Object client, RemoveRandomWithObject requestGrabber, ObjectContainer container) {
		grabArraysByClient.put(client, requestGrabber);
		RemoveRandomWithObject[] newArrays = new RemoveRandomWithObject[grabArrays.length+1];
		System.arraycopy(grabArrays, 0, newArrays, 0, grabArrays.length);
		newArrays[grabArrays.length] = requestGrabber;
		grabArrays = newArrays;
		if(persistent) {
			container.set(grabArraysByClient);
			container.set(this);
		}
	}

	public synchronized RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		/** Count of arrays that have items but didn't return anything because of exclusions */
		int excluded = 0;
		final int MAX_EXCLUDED = 10;
		while(true) {
			if(grabArrays.length == 0) return null;
			if(grabArrays.length == 1) {
				// Optimise the common case
				RemoveRandomWithObject rga = grabArrays[0];
				if(persistent)
					container.activate(rga, 1);
				RandomGrabArrayItem item = rga.removeRandom(excluding, container, context);
				if(rga.isEmpty()) {
					Object client = rga.getObject();
					if(persistent)
						container.activate(client, 1);
					if(logMINOR)
						Logger.minor(this, "Removing only grab array (0) : "+rga+" for "+rga.getObject()+" (is empty)");
					grabArraysByClient.remove(client);
					grabArrays = new RemoveRandomWithObject[0];
					if(persistent)
						container.set(this);
				}
				if(logMINOR)
					Logger.minor(this, "Returning (one item only) "+item+" for "+rga);
				return item;
			}
			if(grabArrays.length == 2) {
				// Another simple common case
				int x = context.fastWeakRandom.nextBoolean() ? 1 : 0;
				RemoveRandomWithObject rga = grabArrays[x];
				if(persistent)
					container.activate(rga, 1);
				RemoveRandomWithObject firstRGA = rga;
				if(rga == null) {
					Logger.error(this, "rga = null on "+this);
					if(container != null && !container.ext().isActive(this))
						Logger.error(this, "NOT ACTIVE!!");
					if(grabArrays[1-x] == null) {
						Logger.error(this, "other rga is also null on "+this);
						if(grabArraysByClient == null) {
							Logger.error(this, "as is grabArraysByClient");
							// Let it NPE
						} else {
							if(grabArraysByClient.isEmpty()) {
								Logger.error(this, "grabArraysByClient is also empty");
								return null;
							}
						}
					} else {
						RemoveRandomWithObject valid = grabArrays[1-x];
						if(grabArraysByClient.size() != 1) {
							Logger.error(this, "Grab arrays by client size should be 1 (since there is 1 non-null), but is "+grabArraysByClient.size());
							grabArraysByClient.clear();
							if(persistent) {
								container.activate(valid, 1);
								Object client = valid.getObject();
								container.activate(client, 1);
								grabArraysByClient.put(client, valid);
							}
						}
						Logger.error(this, "grabArrays["+(1-x)+"] is valid but ["+x+"] is null, correcting...");
						grabArrays = new RemoveRandomWithObject[] { grabArrays[1-x] };
						continue;
					}
				}
				RandomGrabArrayItem item = rga.removeRandom(excluding, container, context);
				if(item == null) {
					x = 1-x;
					rga = grabArrays[x];
					if(persistent)
						container.activate(rga, 1);
					item = rga.removeRandom(excluding, container, context);
					if(firstRGA.isEmpty() && rga.isEmpty()) {
						Object rgaClient = rga.getObject();
						Object firstClient = firstRGA.getObject();
						if(persistent) {
							container.activate(rgaClient, 1);
							container.activate(firstClient, 1);
						}
						grabArraysByClient.remove(rgaClient);
						grabArraysByClient.remove(firstClient);
						grabArrays = new RemoveRandomWithObject[0];
						if(persistent)
							container.set(this);
					} else if(firstRGA.isEmpty()) {
						if(persistent) {
							container.activate(firstRGA, 1);
						}
						Object firstClient = firstRGA.getObject();
						if(persistent) {
							container.activate(firstClient, 1);
						}
						grabArraysByClient.remove(firstClient);
						grabArrays = new RemoveRandomWithObject[] { rga };
						if(persistent)
							container.set(this);
					}
					if(persistent) {
						container.deactivate(rga, 1);
						container.deactivate(firstRGA, 1);
					}
					if(logMINOR)
						Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
					return item;
				} else {
					if(persistent)
						container.deactivate(rga, 1);
					if(logMINOR)
						Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
					return item;
				}
			}
			int x = context.fastWeakRandom.nextInt(grabArrays.length);
			RemoveRandomWithObject rga = grabArrays[x];
			if(persistent)
				container.activate(rga, 1);
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" on "+this);
			RandomGrabArrayItem item = rga.removeRandom(excluding, container, context);
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						(item==null ? "" : (" cancelled="+item.isEmpty(container)+")"))+" rga.isEmpty="+rga.isEmpty());
			// Just because the item is cancelled does not necessarily mean the whole client is.
			// E.g. a segment may return cancelled because it is decoding, that doesn't mean
			// other segments are cancelled. So just go around the loop in that case.
			final int grabArraysLength = grabArrays.length;
			if(rga.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
				Object client = rga.getObject();
				if(persistent)
					container.activate(client, 1);
				grabArraysByClient.remove(client);
				RemoveRandomWithObject[] newArray = new RemoveRandomWithObject[grabArraysLength > 1 ? grabArraysLength-1 : 0];
				if(x > 0)
					System.arraycopy(grabArrays, 0, newArray, 0, x);
				if(x < grabArraysLength-1)
					System.arraycopy(grabArrays, x+1, newArray, x, grabArraysLength - (x+1));
				grabArrays = newArray;
				if(persistent)
					container.set(this);
			}
			if(item == null) {
				if(!rga.isEmpty()) {
					// Hmmm...
					excluded++;
					if(excluded > MAX_EXCLUDED) {
						Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArraysLength, new Exception("error"));
						if(persistent)
							container.deactivate(rga, 1);
						return null;
					}
				}
				if(persistent)
					container.deactivate(rga, 1);
				continue;
			}
			if(persistent)
				container.deactivate(rga, 1);
			if(item.isEmpty(container)) continue;
			return item;
		}
	}

	public synchronized boolean isEmpty() {
		return grabArrays.length == 0;
	}
	
	public boolean persistent() {
		return persistent;
	}

	public int size() {
		return grabArrays.length;
	}

}
