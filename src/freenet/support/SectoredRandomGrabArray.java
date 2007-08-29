package freenet.support;

import java.util.HashMap;

import freenet.crypt.RandomSource;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned.
 */
public class SectoredRandomGrabArray implements RemoveRandom {

	private final HashMap grabArraysByClient;
	private RemoveRandomWithObject[] grabArrays;
	private final RandomSource rand;
	
	public SectoredRandomGrabArray(RandomSource rand) {
		this.rand = rand;
		this.grabArraysByClient = new HashMap();
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
		return (RemoveRandomWithObject) grabArraysByClient.get(client);
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

	public synchronized RandomGrabArrayItem removeRandom() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		while(true) {
			if(grabArrays.length == 0) return null;
			int x = rand.nextInt(grabArrays.length);
			RemoveRandomWithObject rga = grabArrays[x];
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" : "+rga.getObject());
			RandomGrabArrayItem item = rga.removeRandom();
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						(item==null ? "" : (" cancelled="+item.isCancelled()+")"))+" rga.isEmpty="+rga.isEmpty());
			// Just because the item is cancelled does not necessarily mean the whole client is.
			// E.g. a segment may return cancelled because it is decoding, that doesn't mean
			// other segments are cancelled. So just go around the loop in that case.
			if(rga.isEmpty() || (item == null)) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" for "+rga.getObject()+" (is empty)");
				Object client = rga.getObject();
				grabArraysByClient.remove(client);
				RemoveRandomWithObject[] newArray = new RemoveRandomWithObject[grabArrays.length-1];
				if(x > 0)
					System.arraycopy(grabArrays, 0, newArray, 0, x);
				if(x < grabArrays.length-1)
					System.arraycopy(grabArrays, x+1, newArray, x, grabArrays.length - (x+1));
				grabArrays = newArray;
			}
			if(item == null) continue;
			if(item.isCancelled()) continue;
			return item;
		}
	}

	public synchronized boolean isEmpty() {
		return grabArrays.length == 0;
	}
	
}
