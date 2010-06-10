package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.support.Logger.LoggerPriority;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned.
 */
public class SectoredRandomGrabArray implements RemoveRandom, RemoveRandomParent {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LoggerPriority.MINOR, this);
			}
		});
	}
	

	/*
	 * Yes, this is O(n). No, I don't care.
	 * 
	 * Using a Db4oMap results in stuff getting reactivated during the commit
	 * phase, and not deactivated. This makes keeping stuff that shouldn't be
	 * activated deactivated impossible, resulting in more memory usage. more
	 * Full GC's, more object churn, and hence more CPU usage. Also Db4oMap is
	 * deprecated.
	 * 
	 * Using a HashMap populated in objectOnActivate() doesn't work either,
	 * because it ends up comparing deactivated clients with activated ones.
	 * This will result in NPEs, and unnecessary code complexity to fix them.
	 * 
	 * IMHO it's not worth bothering with a hashtable if it's less than 1000
	 * or so items anyway. If size does become a problem we will need to 
	 * implement our own activation aware hashtable class, which stores the 
	 * full hashCode, and matches on == object identity, so that we don't need
	 * to activate on comparison.
	 */
	private RemoveRandomWithObject[] grabArrays;
	private Object[] grabClients;
	private final boolean persistent;
	private final RemoveRandomParent parent;
	
	public SectoredRandomGrabArray(boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		this.persistent = persistent;
		grabClients = new Object[0];
		grabArrays = new RemoveRandomWithObject[0];
		this.parent = parent;
	}

	/**
	 * Add directly to a RandomGrabArrayWithClient under us. */
	public synchronized void add(Object client, RandomGrabArrayItem item, ObjectContainer container) {
		if(item.persistent() != persistent) throw new IllegalArgumentException("item.persistent()="+item.persistent()+" but array.persistent="+persistent+" item="+item+" array="+this);
		RandomGrabArrayWithClient rga;
		int clientIndex = haveClient(client);
		if(clientIndex == -1) {
			if(logMINOR)
				Logger.minor(this, "Adding new RGAWithClient for "+client+" on "+this+" for "+item);
			rga = new RandomGrabArrayWithClient(client, persistent, container, this);
			addElement(client, rga);
			if(persistent) {
				container.store(rga);
				container.store(this);
			}
		} else {
			rga = (RandomGrabArrayWithClient) grabArrays[clientIndex];
			if(persistent)
				container.activate(rga, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
		rga.add(item, container);
		if(persistent)
			container.deactivate(rga, 1);
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	}

	private void addElement(Object client, RemoveRandomWithObject rga) {
		int len = grabArrays.length;
		RemoveRandomWithObject[] newArrays = new RemoveRandomWithObject[len+1];
		System.arraycopy(grabArrays, 0, newArrays, 0, len);
		newArrays[len] = rga;
		grabArrays = newArrays;
		
		Object[] newClients = new Object[len+1];
		System.arraycopy(grabClients, 0, newClients, 0, len);
		newClients[len] = client;
		grabClients = newClients;
	}

	private synchronized int haveClient(Object client) {
		for(int i=0;i<grabClients.length;i++) {
			if(grabClients[i] == client) return i;
		}
		return -1;
	}

	/**
	 * Get a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized RemoveRandomWithObject getGrabber(Object client) {
		int idx = haveClient(client);
		if(idx == -1) return null;
		else return grabArrays[idx];
	}
	
	public synchronized Object getClient(int x) {
		return grabClients[x];
	}

	/**
	 * Put a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public synchronized void addGrabber(Object client, RemoveRandomWithObject requestGrabber, ObjectContainer container) {
		if(requestGrabber.getObject() != client)
			throw new IllegalArgumentException("Client not equal to RemoveRandomWithObject's client: client="+client+" rr="+requestGrabber+" his object="+requestGrabber.getObject());
		addElement(client, requestGrabber);
		if(persistent) {
			container.store(this);
		}
	}

	public synchronized RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context) {
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
						Logger.minor(this, "Removing only grab array (0) : "+rga);
					grabArrays = new RemoveRandomWithObject[0];
					grabClients = new Object[0];
					if(persistent) {
						container.store(this);
						rga.removeFrom(container);
					}
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
					} else {
						RemoveRandomWithObject valid = grabArrays[1-x];
						Logger.error(this, "grabArrays["+(1-x)+"] is valid but ["+x+"] is null, correcting...");
						grabArrays = new RemoveRandomWithObject[] { grabArrays[1-x] };
						grabClients = new Object[] { grabClients[1-x] };
						if(persistent) {
							container.store(this);
						}
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
						grabArrays = new RemoveRandomWithObject[0];
						grabClients = new Object[0];
						if(persistent) {
							container.store(this);
							firstRGA.removeFrom(container);
							rga.removeFrom(container);
						}
					} else if(firstRGA.isEmpty()) {
						if(persistent) {
							container.activate(firstRGA, 1);
						}
						grabArrays = new RemoveRandomWithObject[] { rga };
						grabClients = new Object[] { grabClients[x] };
						if(persistent) {
							container.store(this);
							firstRGA.removeFrom(container);
						}
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
			if(rga.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
				removeElement(x);
				if(persistent) {
					container.store(this);
					rga.removeFrom(container);
				}
			}
			if(item == null) {
				if(!rga.isEmpty()) {
					// Hmmm...
					excluded++;
					if(excluded > MAX_EXCLUDED) {
						Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArrays.length, new Exception("error"));
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

	private synchronized void removeElement(int x) {
		final int grabArraysLength = grabArrays.length;
		int newLen = grabArraysLength > 1 ? grabArraysLength-1 : 0;
		RemoveRandomWithObject[] newArray = new RemoveRandomWithObject[newLen];
		if(x > 0)
			System.arraycopy(grabArrays, 0, newArray, 0, x);
		if(x < grabArraysLength-1)
			System.arraycopy(grabArrays, x+1, newArray, x, grabArraysLength - (x+1));
		grabArrays = newArray;
		
		Object[] newClients = new Object[newLen];
		if(x > 0)
			System.arraycopy(grabClients, 0, newClients, 0, x);
		if(x < grabArraysLength-1)
			System.arraycopy(grabClients, x+1, newClients, x, grabArraysLength - (x+1));
		grabClients = newClients;
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
	
	public void removeFrom(ObjectContainer container) {
		if(grabArrays != null && grabArrays.length != 0) {
			for(RemoveRandomWithObject rr : grabArrays) {
				if(rr != null) {
					Logger.error(this, "NOT EMPTY REMOVING "+this+" : "+rr);
					return;
				}
			}
		}
		container.delete(this);
	}

	public void maybeRemove(RemoveRandom r, ObjectContainer container) {
		int count = 0;
		synchronized(this) {
			while(true) {
				int found = -1;
				for(int i=0;i<grabArrays.length;i++) {
					if(grabArrays[i] == r) {
						found = i;
						break;
					}
				}
				if(found != -1) {
					count++;
					if(count > 1) Logger.error(this, "Found "+r+" many times in "+this, new Exception("error"));
					removeElement(found);
				} else {
					break;
				}
			}
		}
		if(count == 0) Logger.error(this, "Not in parent: "+r+" for "+this, new Exception("error"));
		else if(persistent) {
			container.store(this);
			r.removeFrom(container);
		}
	}

}
