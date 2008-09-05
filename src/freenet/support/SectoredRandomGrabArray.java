package freenet.support;

import java.util.HashMap;

import freenet.crypt.RandomSource;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned.
 */
public class SectoredRandomGrabArray implements RemoveRandom {

	private final HashMap<Object, RemoveRandomWithObject> grabArraysByClient;
	private RemoveRandomWithObject[] grabArrays;
	private final RandomSource rand;
	
	public SectoredRandomGrabArray(RandomSource rand) {
		this.rand = rand;
		this.grabArraysByClient = new HashMap<Object, RemoveRandomWithObject>();
		grabArrays = new RemoveRandomWithObject[0];
	}

	/**
	 * Add directly to a RandomGrabArrayWithClient under us. */
	public synchronized void add(Object client, RandomGrabArrayItem item) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		RandomGrabArrayWithClient rga;
		if(!grabArraysByClient.containsKey(client)) {
			if(logMINOR)
				Logger.minor(this, "Adding new RGAWithClient for "+client+" on "+this+" for "+item);
			rga = new RandomGrabArrayWithClient(client, rand);
			RemoveRandomWithObject[] newArrays = new RemoveRandomWithObject[grabArrays.length+1];
			System.arraycopy(grabArrays, 0, newArrays, 0, grabArrays.length);
			newArrays[grabArrays.length] = rga;
			grabArrays = newArrays;
			grabArraysByClient.put(client, rga);
		} else {
			rga = (RandomGrabArrayWithClient) grabArraysByClient.get(client);
		}
		if(logMINOR)
			Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
		rga.add(item);
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	}

	/**
	 * Get a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized RemoveRandomWithObject getGrabber(Object client) {
		return grabArraysByClient.get(client);
	}

	/**
	 * Put a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized void addGrabber(Object client, RemoveRandomWithObject requestGrabber) {
		grabArraysByClient.put(client, requestGrabber);
		RemoveRandomWithObject[] newArrays = new RemoveRandomWithObject[grabArrays.length+1];
		System.arraycopy(grabArrays, 0, newArrays, 0, grabArrays.length);
		newArrays[grabArrays.length] = requestGrabber;
		grabArrays = newArrays;
	}

	public synchronized RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		/** Count of arrays that have items but didn't return anything because of exclusions */
		int excluded = 0;
		final int MAX_EXCLUDED = 10;
		while(true) {
			if(grabArrays.length == 0) return null;
			if(grabArrays.length == 1) {
				// Optimise the common case
				RemoveRandomWithObject rga = grabArrays[0];
				RandomGrabArrayItem item = rga.removeRandom(excluding);
				if(rga.isEmpty()) {
					if(logMINOR)
						Logger.minor(this, "Removing only grab array (0) : "+rga+" for "+rga.getObject()+" (is empty)");
					Object client = rga.getObject();
					grabArraysByClient.remove(client);
					grabArrays = new RemoveRandomWithObject[0];
				}
				if(logMINOR)
					Logger.minor(this, "Returning (one item only) "+item+" for "+rga+" for "+rga.getObject());
				return item;
			}
			if(grabArrays.length == 2) {
				// Another simple common case
				int x = rand.nextBoolean() ? 1 : 0;
				RemoveRandomWithObject rga = grabArrays[x];
				RemoveRandomWithObject firstRGA = rga;
				RandomGrabArrayItem item = rga.removeRandom(excluding);
				if(item == null) {
					x = 1-x;
					rga = grabArrays[x];
					item = rga.removeRandom(excluding);
					if(firstRGA.isEmpty() && rga.isEmpty()) {
						grabArraysByClient.remove(rga.getObject());
						grabArraysByClient.remove(firstRGA.getObject());
						grabArrays = new RemoveRandomWithObject[0];
					} else if(firstRGA.isEmpty()) {
						grabArraysByClient.remove(firstRGA.getObject());
						grabArrays = new RemoveRandomWithObject[] { rga };
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
			int x = rand.nextInt(grabArrays.length);
			RemoveRandomWithObject rga = grabArrays[x];
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" : "+rga.getObject()+" on "+this);
			RandomGrabArrayItem item = rga.removeRandom(excluding);
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
	
}
