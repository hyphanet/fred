package freenet.support;

import java.util.Arrays;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestSelector;
import freenet.client.async.CooldownTracker;
import freenet.client.async.HasCooldownCacheItem;

/**
 * Like RandomGrabArray, but there is an equal chance of any given client's requests being
 * returned. Again, not persistent; this is reconstructed on restart.
 * 
 * LOCKING: There is a single lock for the entire tree, the ClientRequestSelector. This must be 
 * taken before calling any methods on RGA or SRGA. See the javadocs there for deeper explanation.
 * 
 * A lot of this is over-complicated and over-expensive because of db4o. A lot of it is O(n).
 * This is all kept in RAM now so we can change it at will, plus there is only one object 
 * queued per splitfile now, so memory pressure is much less of an issue. 
 * FIXME Simplify and improve performance!
 */
public class SectoredRandomGrabArray implements RemoveRandom, RemoveRandomParent, HasCooldownCacheItem {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(SectoredRandomGrabArray.class);
	}

	private RemoveRandomWithObject[] grabArrays;
	private Object[] grabClients;
	private RemoveRandomParent parent;
	private final ClientRequestSelector root;
	
	public SectoredRandomGrabArray(RemoveRandomParent parent, ClientRequestSelector root) {
		grabClients = new Object[0];
		grabArrays = new RemoveRandomWithObject[0];
		this.parent = parent;
		this.root = root;
	}

	/** Add directly to a RandomGrabArrayWithClient under us. */
	public void add(Object client, RandomGrabArrayItem item, ClientContext context) {
	    synchronized(root) {
		RandomGrabArrayWithClient rga;
		int clientIndex = haveClient(client);
		if(clientIndex == -1) {
			if(logMINOR)
				Logger.minor(this, "Adding new RGAWithClient for "+client+" on "+this+" for "+item);
			rga = new RandomGrabArrayWithClient(client, this, root);
			addElement(client, rga);
		} else {
			rga = (RandomGrabArrayWithClient) grabArrays[clientIndex];
		}
		if(logMINOR)
			Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
		rga.add(item, context);
		if(context != null) {
			// It's safest to always clear the parent too if we have one.
			// FIXME strictly speaking it shouldn't be necessary, investigate callers of clearCachedWakeup(), but be really careful to avoid stalling!
			root.clearCachedWakeup(this);
			if(parent != null)
				root.clearCachedWakeup(parent);
		}
		if(logMINOR)
			Logger.minor(this, "Size now "+grabArrays.length+" on "+this);
	    }
	}

	private void addElement(Object client, RemoveRandomWithObject rga) {
	    synchronized(root) {
		final int len = grabArrays.length;

		grabArrays = Arrays.copyOf(grabArrays, len+1);
		grabArrays[len] = rga;
		
		grabClients = Arrays.copyOf(grabClients, len+1);
		grabClients[len] = client;
	    }
	}

	private int haveClient(Object client) {
	    synchronized(root) {
		for(int i=0;i<grabClients.length;i++) {
			if(grabClients[i] == client) return i;
		}
		return -1;
	    }
	}

	/**
	 * Get a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public RemoveRandomWithObject getGrabber(Object client) {
	    synchronized(root) {
		int idx = haveClient(client);
		if(idx == -1) return null;
		else return grabArrays[idx];
	    }
	}
	
	public Object getClient(int x) {
	    synchronized(root) {
		return grabClients[x];
	    }
	}

	/**
	 * Put a grabber. This lets us use things other than RandomGrabArrayWithClient's, so don't mix calls
	 * to add() with calls to getGrabber/addGrabber!
	 */
	public void addGrabber(Object client, RemoveRandomWithObject requestGrabber, ClientContext context) {
	    synchronized(root) {
		if(requestGrabber.getObject() != client)
			throw new IllegalArgumentException("Client not equal to RemoveRandomWithObject's client: client="+client+" rr="+requestGrabber+" his object="+requestGrabber.getObject());
		addElement(client, requestGrabber);
		if(context != null) {
			root.clearCachedWakeup(this);
			if(parent != null)
				root.clearCachedWakeup(parent);
		}
	    }
	}

	@Override
	public RemoveRandomReturn removeRandom(RandomGrabArrayItemExclusionList excluding, ClientContext context, long now) {
	    synchronized(root) {
		while(true) {
			if(grabArrays.length == 0) return null;
			if(grabArrays.length == 1) {
				return removeRandomOneOnly(excluding, context, now);
			}
			if(grabArrays.length == 2) {
				RemoveRandomReturn ret = removeRandomTwoOnly(excluding, context, now);
				if(ret == null) continue; // Go around loop again, it has reduced to 1 or 0.
				return ret;
			}
			RandomGrabArrayItem item = removeRandomLimited(excluding, context, now);
			if(item != null)
				return new RemoveRandomReturn(item);
			else
				return removeRandomExhaustive(excluding, context, now);
		}
	    }
	}

	private RemoveRandomReturn removeRandomExhaustive(
			RandomGrabArrayItemExclusionList excluding,
			ClientContext context, long now) {
	    synchronized(root) {
		long wakeupTime = Long.MAX_VALUE;
		if(grabArrays.length == 0) return null;
		int x = context.fastWeakRandom.nextInt(grabArrays.length);
		for(int i=0;i<grabArrays.length;i++) {
			x++;
			if(x >= grabArrays.length) x = 0;
			RemoveRandomWithObject rga = grabArrays[x];
			long excludeTime = excluding.excludeSummarily(rga, this, now);
			if(excludeTime > 0) {
				if(wakeupTime > excludeTime) wakeupTime = excludeTime;
				continue;
			}
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" on "+this);
			
			RandomGrabArrayItem item = null;
			RemoveRandomReturn val = rga.removeRandom(excluding, context, now);
			if(val != null) {
				if(val.item != null)
					item = val.item;
				else {
					if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
				}
			}
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						" rga.isEmpty="+rga.isEmpty());
			if(item != null) {
				return new RemoveRandomReturn(item);
			} else if(rga.isEmpty()) {
				if(logMINOR)
					Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
				removeElement(x);
			}
		}
		root.setCachedWakeup(wakeupTime, this, parent, context);
		return new RemoveRandomReturn(wakeupTime);
	    }
	}

	private RandomGrabArrayItem removeRandomLimited(
			RandomGrabArrayItemExclusionList excluding,
			ClientContext context, long now) {
	    synchronized(root) {
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
			long excludeTime = excluding.excludeSummarily(rga, this, now);
			if(excludeTime > 0) {
				excluded++;
				if(excluded > MAX_EXCLUDED) {
					Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArrays.length, new Exception("error"));
					return null;
				}
				continue;
			}
			if(logMINOR)
				Logger.minor(this, "Picked "+x+" of "+grabArrays.length+" : "+rga+" on "+this);
			
			RandomGrabArrayItem item = null;
			RemoveRandomReturn val = rga.removeRandom(excluding, context, now);
			if(val != null && val.item != null) item = val.item;
			if(logMINOR)
				Logger.minor(this, "RGA has picked "+x+"/"+grabArrays.length+": "+item+
						" rga.isEmpty="+rga.isEmpty());
			// If it is not empty but returns null we exclude it, and count the exclusion.
			// If it is empty we remove it, and don't count the exclusion.
			if(item != null) {
				return item;
			} else {
				if(rga.isEmpty()) {
					if(logMINOR)
						Logger.minor(this, "Removing grab array "+x+" : "+rga+" (is empty)");
					removeElement(x);
				} else {
					excluded++;
					if(excluded > MAX_EXCLUDED) {
						Logger.normal(this, "Too many sub-arrays are entirely excluded on "+this+" length = "+grabArrays.length, new Exception("error"));
						return null;
					}
				}
				continue;
			}
		}
	    }
	}

	private RemoveRandomReturn removeRandomTwoOnly(
			RandomGrabArrayItemExclusionList excluding,
			ClientContext context, long now) {
	    synchronized(root) {
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
				return null;
			} else {
				Logger.error(this, "grabArrays["+(1-x)+"] is valid but ["+x+"] is null, correcting...");
				grabArrays = new RemoveRandomWithObject[] { grabArrays[1-x] };
				grabClients = new Object[] { grabClients[1-x] };
				return null;
			}
		}
		RandomGrabArrayItem item = null;
		RemoveRandomReturn val = null;
		if(logMINOR) Logger.minor(this, "Only 2, trying "+rga);
		long excludeTime = excluding.excludeSummarily(rga, this, now);
		if(excludeTime > 0) {
			wakeupTime = excludeTime;
			rga = null;
			firstRGA = null;
		} else {
			val = rga.removeRandom(excluding, context, now);
			if(val != null) {
				if(val.item != null)
					item = val.item;
				else {
					if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
				}
			}
		}
		if(item != null) {
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
				root.setCachedWakeup(wakeupTime, this, parent, context);
				return new RemoveRandomReturn(wakeupTime);
			}
			excludeTime = excluding.excludeSummarily(rga, this, now);
			if(excludeTime > 0) {
				if(wakeupTime > excludeTime) wakeupTime = excludeTime;
				rga = null;
			} else {
				val = rga.removeRandom(excluding, context, now);
				if(val != null) {
					if(val.item != null)
						item = val.item;
					else {
						if(wakeupTime > val.wakeupTime) wakeupTime = val.wakeupTime;
					}
				}
			}
			if(firstRGA != null && firstRGA.isEmpty() && rga != null && rga.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Removing both on "+this+" : "+firstRGA+" and "+rga+" are empty");
				grabArrays = new RemoveRandomWithObject[0];
				grabClients = new Object[0];
			} else if(firstRGA != null && firstRGA.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Removing first: "+firstRGA+" is empty on "+this);
				grabArrays = new RemoveRandomWithObject[] { grabArrays[x] }; // don't use RGA, it may be nulled out
				grabClients = new Object[] { grabClients[x] };
			}
			if(logMINOR)
				Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
			if(item == null) {
				if(grabArrays.length == 0)
					return null; // Remove this as well
				root.setCachedWakeup(wakeupTime, this, parent, context);
				return new RemoveRandomReturn(wakeupTime);
			} else return new RemoveRandomReturn(item);
		}
	    }
	}

	private RemoveRandomReturn removeRandomOneOnly(
			RandomGrabArrayItemExclusionList excluding,
			ClientContext context, long now) {
	    synchronized(root) {
		long wakeupTime = Long.MAX_VALUE;
		// Optimise the common case
		RemoveRandomWithObject rga = grabArrays[0];
		if(logMINOR) Logger.minor(this, "Only one RGA: "+rga);
		long excludeTime = excluding.excludeSummarily(rga, this, now);
		if(excludeTime > 0)
			return new RemoveRandomReturn(excludeTime);
		if(rga == null) {
			Logger.error(this, "Only one entry and that is null");
			// We are sure
			grabArrays = new RemoveRandomWithObject[0];
			grabClients = new Object[0];
			return null;
		}
		RemoveRandomReturn val = rga.removeRandom(excluding, context, now);
		RandomGrabArrayItem item = null;
		if(val != null) { // val == null => remove it
			if(val.item != null)
				item = val.item;
			else {
				wakeupTime = val.wakeupTime;
			}
		}
		if(rga.isEmpty()) {
			if(logMINOR)
				Logger.minor(this, "Removing only grab array (0) : "+rga);
			grabArrays = new RemoveRandomWithObject[0];
			grabClients = new Object[0];
		}
		if(logMINOR)
			Logger.minor(this, "Returning (one item only) "+item+" for "+rga);
		if(item == null) {
			if(grabArrays.length == 0) {
				if(logMINOR) Logger.minor(this, "Arrays are empty on "+this);
				return null; // Remove this as well
			}
			root.setCachedWakeup(wakeupTime, this, parent, context);
			return new RemoveRandomReturn(wakeupTime);
		} else return new RemoveRandomReturn(item);
	    }
	}

	private void removeElement(int x) {
	    synchronized(root) {
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
	}

	public boolean isEmpty() {
	    synchronized(root) {
		return grabArrays.length == 0;
	    }
	}
	
	public int size() {
	    synchronized(root) {
		return grabArrays.length;
	    }
	}
	
	@Override
	public void maybeRemove(RemoveRandom r, ClientContext context) {
		int count = 0;
		int finalSize;
		synchronized(root) {
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
			root.removeCachedWakeup(r);
		}
		if(finalSize == 0 && parent != null) {
			root.removeCachedWakeup(this);
			parent.maybeRemove(this, context);
		}
	}

	public void moveElementsTo(SectoredRandomGrabArray newTopLevel,
			boolean canCommit) {
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
			if(existingGrabber != null)
				System.out.println("Merging with existing grabber for client "+client);
			if(existingGrabber != null) {
				grabber.moveElementsTo(existingGrabber,  canCommit);
			} else {
				grabber.setParent(newTopLevel);
				if(grabber.getObject() == null && client != null) {
					Logger.error(this, "Minor corruption on migration: client is "+client+" but grabber reports null, correcting");
					grabber.setObject(client);
				}
				newTopLevel.addGrabber(client, grabber, null);
			}
			grabArrays[i] = null;
			grabClients[i] = null;
		}
		grabArrays = new RemoveRandomWithObject[0];
		grabClients = new Object[0];
	}

	@Override
	public void moveElementsTo(RemoveRandom existingGrabber,
			boolean canCommit) {
		if(existingGrabber instanceof SectoredRandomGrabArray)
			moveElementsTo((SectoredRandomGrabArray)existingGrabber, canCommit);
		else
			throw new IllegalArgumentException();
	}

	@Override
	public void setParent(RemoveRandomParent newParent) {
		this.parent = newParent;
	}
	
}
