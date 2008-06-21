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
				container.set(this);
			}
		} else {
			rga = (RandomGrabArrayWithClient) grabArraysByClient.get(client);
		}
		if(logMINOR)
			Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
		// rga is auto-activated to depth 1...
		rga.add(item, container);
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	}

	/**
	 * Get a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized RemoveRandomWithObject getGrabber(Object client) {
		return (RemoveRandomWithObject) grabArraysByClient.get(client);
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
					if(logMINOR)
						Logger.minor(this, "Removing only grab array (0) : "+rga+" for "+rga.getObject()+" (is empty)");
					Object client = rga.getObject();
					grabArraysByClient.remove(client);
					grabArrays = new RemoveRandomWithObject[0];
					if(persistent)
						container.set(this);
				}
				if(logMINOR)
					Logger.minor(this, "Returning (one item only) "+item+" for "+rga+" for "+rga.getObject());
				return item;
			}
			if(grabArrays.length == 2) {
				// Another simple common case
				int x = context.fastWeakRandom.nextBoolean() ? 1 : 0;
				RemoveRandomWithObject rga = grabArrays[x];
				if(persistent)
					container.activate(rga, 1);
				RemoveRandomWithObject firstRGA = rga;
				RandomGrabArrayItem item = rga.removeRandom(excluding, container, context);
				if(item == null) {
					x = 1-x;
					rga = grabArrays[x];
					if(persistent)
						container.activate(rga, 1);
					item = rga.removeRandom(excluding, container, context);
					if(firstRGA.isEmpty() && rga.isEmpty()) {
						grabArraysByClient.remove(rga.getObject());
						grabArraysByClient.remove(firstRGA.getObject());
						grabArrays = new RemoveRandomWithObject[0];
						if(persistent)
							container.set(this);
					} else if(firstRGA.isEmpty()) {
						grabArraysByClient.remove(firstRGA.getObject());
						grabArrays = new RemoveRandomWithObject[] { rga };
						if(persistent)
							container.set(this);
					}
					if(logMINOR)
						Logger.minor(this, "Returning (two items only) "+item+" for "+rga+" for "+rga.getObject());
					return item;
				} else {
					if(logMINOR)
						Logger.minor(this, "Returning (two items only) "+item+" for "+rga+" for "+rga.getObject());
					return item;
				}
			}
			int x = context.fastWeakRandom.nextInt(grabArrays.length);
			RemoveRandomWithObject rga = grabArrays[x];
			if(persistent)
				container.activate(rga, 1);
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" : "+rga.getObject()+" on "+this);
			RandomGrabArrayItem item = rga.removeRandom(excluding, container, context);
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						(item==null ? "" : (" cancelled="+item.isEmpty()+")"))+" rga.isEmpty="+rga.isEmpty());
			// Just because the item is cancelled does not necessarily mean the whole client is.
			// E.g. a segment may return cancelled because it is decoding, that doesn't mean
			// other segments are cancelled. So just go around the loop in that case.
			final int grabArraysLength = grabArrays.length;
			if(rga.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" for "+rga.getObject()+" (is empty)");
				Object client = rga.getObject();
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
						return null;
					}
				}
				continue;
			}
			if(item.isEmpty()) continue;
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
