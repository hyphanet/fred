package freenet.support;

import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned.
 */
public class SectoredRandomGrabArray implements RemoveRandom, RemoveRandomParent, HasCooldownCacheItem {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(SectoredRandomGrabArray.class);
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
	protected final boolean persistent;
	private RemoveRandomParent parent;
	
	public SectoredRandomGrabArray(boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		this.persistent = persistent;
		grabClients = new Object[0];
		grabArrays = new RemoveRandomWithObject[0];
		this.parent = parent;
	}

	/** Add directly to a RandomGrabArrayWithClient under us. */
	public synchronized void add(Object client, RandomGrabArrayItem item, ObjectContainer container, ClientContext context) {
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
		rga.add(item, container, context);
		if(persistent)
			container.deactivate(rga, 1);
		if(context != null) {
			// It's safest to always clear the parent too if we have one.
			// FIXME strictly speaking it shouldn't be necessary, investigate callers of clearCachedWakeup(), but be really careful to avoid stalling!
			context.cooldownTracker.clearCachedWakeup(this, persistent, container);
			if(parent != null)
				context.cooldownTracker.clearCachedWakeup(parent, persistent, container);
		}
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	}

	private synchronized void addElement(Object client, RemoveRandomWithObject rga) {
		final int len = grabArrays.length;

		grabArrays = Arrays.copyOf(grabArrays, len+1);
		grabArrays[len] = rga;
		
		grabClients = Arrays.copyOf(grabClients, len+1);
		grabClients[len] = client;
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
	public synchronized void addGrabber(Object client, RemoveRandomWithObject requestGrabber, ObjectContainer container, ClientContext context) {
		if(requestGrabber.getObject() != client)
			throw new IllegalArgumentException("Client not equal to RemoveRandomWithObject's client: client="+client+" rr="+requestGrabber+" his object="+requestGrabber.getObject());
		addElement(client, requestGrabber);
		if(persistent) container.store(this);
		if(context != null) {
			context.cooldownTracker.clearCachedWakeup(this, persistent, container);
			if(parent != null)
				context.cooldownTracker.clearCachedWakeup(parent, persistent, container);
		}
	}

	@Override
	public synchronized RemoveRandomReturn removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context, long now) {
		while(true) {
			if(grabArrays.length == 0) return null;
			if(grabArrays.length == 1) {
				return removeRandomOneOnly(excluding, container, context, now);
			}
			if(grabArrays.length == 2) {
				RemoveRandomReturn ret = removeRandomTwoOnly(excluding, container, context, now);
				if(ret == null) continue; // Go around loop again, it has reduced to 1 or 0.
				return ret;
			}
			RandomGrabArrayItem item = removeRandomLimited(excluding, container, context, now);
			if(item != null)
				return new RemoveRandomReturn(item);
			else
				return removeRandomExhaustive(excluding, container, context, now);
		}
	}

	private synchronized RemoveRandomReturn removeRandomExhaustive(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		long wakeupTime = Long.MAX_VALUE;
		if(grabArrays.length == 0) return null;
		int x = context.fastWeakRandom.nextInt(grabArrays.length);
		for(int i=0;i<grabArrays.length;i++) {
			x++;
			if(x >= grabArrays.length) x = 0;
			RemoveRandomWithObject rga = grabArrays[x];
			long excludeTime = excluding.excludeSummarily(rga, this, container, persistent, now);
			if(excludeTime > 0) {
				if(wakeupTime > excludeTime) wakeupTime = excludeTime;
				continue;
			}
			if(persistent)
				container.activate(rga, 1);
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" on "+this);
			
			RandomGrabArrayItem item = null;
			RemoveRandomReturn val = rga.removeRandom(excluding, container, context, now);
			if(val != null) {
				if(val.item != null)
					item = val.item;
				else {
					if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
				}
			}
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						" rga.isEmpty="+rga.isEmpty(container));
			if(item != null) {
				if(persistent)
					container.deactivate(rga, 1);
				return new RemoveRandomReturn(item);
			} else if(rga.isEmpty(container)) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
				removeElement(x);
				if(persistent) {
					container.store(this);
					rga.removeFrom(container);
				}
				if(persistent)
					container.deactivate(rga, 1);
			}
		}
		context.cooldownTracker.setCachedWakeup(wakeupTime, this, parent, persistent, container, context);
		return new RemoveRandomReturn(wakeupTime);
	}

	private synchronized RandomGrabArrayItem removeRandomLimited(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		/** Count of arrays that have items but didn't return anything because of exclusions */
		final int MAX_EXCLUDED = 10;
		int excluded = 0;
		while(true) {
			if(grabArrays.length == 0) return null;
			int x = context.fastWeakRandom.nextInt(grabArrays.length);
			RemoveRandomWithObject rga = grabArrays[x];
			if(rga == null) {
				// We handle this in the other cases so we should handle it here.
				Logger.error(this, "Slot "+x+" is null for client "+grabClients[x]);
				excluded++;
				if(excluded > MAX_EXCLUDED) {
					Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArrays.length, new Exception("error"));
					return null;
				}
				continue;
			}
			long excludeTime = excluding.excludeSummarily(rga, this, container, persistent, now);
			if(excludeTime > 0) {
				excluded++;
				if(excluded > MAX_EXCLUDED) {
					Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArrays.length, new Exception("error"));
					return null;
				}
				continue;
			}
			if(persistent)
				container.activate(rga, 1);
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" on "+this);
			
			RandomGrabArrayItem item = null;
			RemoveRandomReturn val = rga.removeRandom(excluding, container, context, now);
			if(val != null && val.item != null) item = val.item;
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						" rga.isEmpty="+rga.isEmpty(container));
			// If it is not empty but returns null we exclude it, and count the exclusion.
			// If it is empty we remove it, and don't count the exclusion.
			if(item != null) {
				if(persistent)
					container.deactivate(rga, 1);
				return item;
			} else {
				if(rga.isEmpty(container)) {
					if(logMINOR)
						Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
					removeElement(x);
					if(persistent) {
						container.store(this);
						rga.removeFrom(container);
					}
				} else {
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
		}
	}

	private synchronized RemoveRandomReturn removeRandomTwoOnly(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		long wakeupTime = Long.MAX_VALUE;
		// Another simple common case
		int x = context.fastWeakRandom.nextBoolean() ? 1 : 0;
		RemoveRandomWithObject rga = grabArrays[x];
		RemoveRandomWithObject firstRGA = rga;
		if(rga == null) {
			Logger.error(this, "rga = null on "+this);
			if(grabArrays[1-x] == null) {
				Logger.error(this, "other rga is also null on "+this);
				grabArrays = new RemoveRandomWithObject[0];
				grabClients = new Object[0];
				if(persistent) container.store(this);
				return null;
			} else {
				Logger.error(this, "grabArrays["+(1-x)+"] is valid but ["+x+"] is null, correcting...");
				grabArrays = new RemoveRandomWithObject[] { grabArrays[1-x] };
				grabClients = new Object[] { grabClients[1-x] };
				if(persistent) container.store(this);
				return null;
			}
		}
		RandomGrabArrayItem item = null;
		RemoveRandomReturn val = null;
		if(logMINOR) Logger.minor(this, "Only 2, trying "+rga);
		long excludeTime = excluding.excludeSummarily(rga, this, container, persistent, now);
		if(excludeTime > 0) {
			wakeupTime = excludeTime;
			rga = null;
			firstRGA = null;
		} else {
			if(persistent)
				container.activate(rga, 1);
			val = rga.removeRandom(excluding, container, context, now);
			if(val != null) {
				if(val.item != null)
					item = val.item;
				else {
					if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
				}
			}
		}
		if(item != null) {
			if(persistent)
				container.deactivate(rga, 1);
			if(logMINOR)
				Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
			return new RemoveRandomReturn(item);
		} else {
			x = 1-x;
			rga = grabArrays[x];
			if(rga == null) {
				Logger.error(this, "Other RGA is null later on on "+this);
				grabArrays = new RemoveRandomWithObject[] { grabArrays[1-x] };
				grabClients = new Object[] { grabClients[1-x] };
				if(persistent) container.store(this);
				context.cooldownTracker.setCachedWakeup(wakeupTime, this, parent, persistent, container, context);
				return new RemoveRandomReturn(wakeupTime);
			}
			excludeTime = excluding.excludeSummarily(rga, this, container, persistent, now);
			if(excludeTime > 0) {
				if(wakeupTime > excludeTime) wakeupTime = excludeTime;
				rga = null;
			} else {
				if(persistent)
					container.activate(rga, 1);
				val = rga.removeRandom(excluding, container, context, now);
				if(val != null) {
					if(val.item != null)
						item = val.item;
					else {
						if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
					}
				}
			}
			if(firstRGA != null && firstRGA.isEmpty(container) && rga != null && rga.isEmpty(container)) {
				if(logMINOR) Logger.minor(this, "Removing both on "+this+" : "+firstRGA+" and "+rga+" are empty");
				grabArrays = new RemoveRandomWithObject[0];
				grabClients = new Object[0];
				if(persistent) {
					container.store(this);
					firstRGA.removeFrom(container);
					rga.removeFrom(container);
				}
			} else if(firstRGA != null && firstRGA.isEmpty(container)) {
				if(logMINOR) Logger.minor(this, "Removing first: "+firstRGA+" is empty on "+this);
				grabArrays = new RemoveRandomWithObject[] { grabArrays[x] }; // don't use RGA, it may be nulled out
				grabClients = new Object[] { grabClients[x] };
				if(persistent) {
					container.store(this);
					firstRGA.removeFrom(container);
				}
			}
			if(persistent) {
				if(rga != null) container.deactivate(rga, 1);
				if(firstRGA != null) container.deactivate(firstRGA, 1);
			}
			if(logMINOR)
				Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
			if(item == null) {
				if(grabArrays.length == 0)
					return null; // Remove this as well
				context.cooldownTracker.setCachedWakeup(wakeupTime, this, parent, persistent, container, context);
				return new RemoveRandomReturn(wakeupTime);
			} else return new RemoveRandomReturn(item);
		}
	}

	private synchronized RemoveRandomReturn removeRandomOneOnly(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		long wakeupTime = Long.MAX_VALUE;
		// Optimise the common case
		RemoveRandomWithObject rga = grabArrays[0];
		if(logMINOR) Logger.minor(this, "Only one RGA: "+rga);
		long excludeTime = excluding.excludeSummarily(rga, this, container, persistent, now);
		if(excludeTime > 0)
			return new RemoveRandomReturn(excludeTime);
		if(rga == null) {
			Logger.error(this, "Only one entry and that is null; persistent="+persistent);
			if(container == null) {
				// We are sure
				grabArrays = new RemoveRandomWithObject[0];
				grabClients = new Object[0];
			}
			return null;
		}
		if(persistent)
			container.activate(rga, 1);
		RemoveRandomReturn val = rga.removeRandom(excluding, container, context, now);
		RandomGrabArrayItem item = null;
		if(val != null) { // val == null => remove it
			if(val.item != null)
				item = val.item;
			else {
				wakeupTime = val.wakeupTime;
			}
		}
		if(rga.isEmpty(container)) {
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
		if(item == null) {
			if(grabArrays.length == 0) {
				if(logMINOR) Logger.minor(this, "Arrays are empty on "+this);
				return null; // Remove this as well
			}
			context.cooldownTracker.setCachedWakeup(wakeupTime, this, parent, persistent, container, context);
			return new RemoveRandomReturn(wakeupTime);
		} else return new RemoveRandomReturn(item);
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

	public synchronized boolean isEmpty(ObjectContainer container) {
		if(container != null && !persistent) {
			boolean stored = container.ext().isStored(this);
			boolean active = container.ext().isActive(this);
			if(stored && !active) {
				Logger.error(this, "Not empty because not active on "+this);
				return false;
			} else if(!stored) {
				Logger.error(this, "Not stored yet passed in container on "+this, new Exception("debug"));
			} else if(stored) {
				throw new IllegalStateException("Stored but not persistent on "+this);
			}
		}
		return grabArrays.length == 0;
	}
	
	@Override
	public boolean persistent() {
		return persistent;
	}

	public synchronized int size() {
		return grabArrays.length;
	}
	
	@Override
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

	@Override
	public void maybeRemove(RemoveRandom r, ObjectContainer container, ClientContext context) {
		int count = 0;
		int finalSize;
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
			finalSize = grabArrays.length;
		}
		if(count == 0) {
			// This is not unusual, it was e.g. removed because of being empty.
			// And it has already been removeFrom()'ed.
			if(logMINOR) Logger.minor(this, "Not in parent: "+r+" for "+this, new Exception("error"));
			context.cooldownTracker.removeCachedWakeup(r, persistent, container);
		} else if(persistent) {
			container.store(this);
			r.removeFrom(container);
		}
		if(finalSize == 0 && parent != null) {
			boolean active = true;
			if(persistent) active = container.ext().isActive(parent);
			if(!active) container.activate(parent, 1);
			context.cooldownTracker.removeCachedWakeup(this, persistent, container);
			parent.maybeRemove(this, container, context);
			if(!active) container.deactivate(parent, 1);
		}
	}

	public void moveElementsTo(SectoredRandomGrabArray newTopLevel,
			ObjectContainer container, boolean canCommit) {
		for(int i=0;i<grabArrays.length;i++) {
			RemoveRandomWithObject grabber = grabArrays[i];
			Object client = grabClients[i];
			if(grabber == null && client == null) continue;
			if(grabber == null && client != null) {
				System.err.println("Grabber is null but client is not null? client is "+client);
				continue;
			}
			if(grabber != null && client == null) {
				System.err.println("Client is null but grabber is not? grabber is "+grabber);
			}
			RemoveRandomWithObject existingGrabber = newTopLevel.getGrabber(client);
			if(persistent()) container.activate(client, 1);
			if(existingGrabber != null)
				System.out.println("Merging with existing grabber for client "+client);
			if(persistent()) container.deactivate(client, 1);
			if(existingGrabber != null) {
				if(persistent) {
					container.activate(grabber, 1);
					container.activate(existingGrabber, 1);
				}
				grabber.moveElementsTo(existingGrabber, container, canCommit);
				grabber.removeFrom(container);
				if(persistent) {
					container.deactivate(grabber, 1);
					container.deactivate(existingGrabber, 1);
				}
			} else {
				if(persistent) container.activate(grabber, 1);
				grabber.setParent(newTopLevel, container);
				if(grabber.getObject() == null && client != null) {
					Logger.error(this, "Minor corruption on migration: client is "+client+" but grabber reports null, correcting");
					grabber.setObject(client, container);
				}
				newTopLevel.addGrabber(client, grabber, container, null);
				if(persistent) container.deactivate(grabber, 1);
			}
			grabArrays[i] = null;
			grabClients[i] = null;
			if(persistent) {
				container.store(this);
				if(canCommit) container.commit();
			}
		}
		grabArrays = new RemoveRandomWithObject[0];
		grabClients = new Object[0];
		if(persistent) {
			container.store(this);
			if(canCommit) container.commit();
		}
	}

	@Override
	public void moveElementsTo(RemoveRandom existingGrabber,
			ObjectContainer container, boolean canCommit) {
		if(existingGrabber instanceof SectoredRandomGrabArray)
			moveElementsTo((SectoredRandomGrabArray)existingGrabber, container, canCommit);
		else
			throw new IllegalArgumentException();
	}

	@Override
	public void setParent(RemoveRandomParent newParent, ObjectContainer container) {
		this.parent = newParent;
		if(persistent()) container.store(this);
	}
	
}
