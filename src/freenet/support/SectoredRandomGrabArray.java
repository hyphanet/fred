package freenet.support;

import java.util.Arrays;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestSelector;
import freenet.client.async.RequestSelectionTreeNode;

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
public class SectoredRandomGrabArray<T, C extends RemoveRandomWithObject<T>> implements RemoveRandom, RemoveRandomParent, RequestSelectionTreeNode {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(SectoredRandomGrabArray.class);
	}

	private RemoveRandomWithObject<T>[] grabArrays;
	private T[] grabClients;
	private RemoveRandomParent parent;
	protected final ClientRequestSelector root;
	private long wakeupTime;

	public SectoredRandomGrabArray(RemoveRandomParent parent, ClientRequestSelector root) {
		grabClients = newClientArray(0);
		grabArrays = newGrabberArray(0);
		this.parent = parent;
		this.root = root;
	}

	protected void addElement(T client, C rga) {
	    synchronized(root) {
		final int len = grabArrays.length;

		grabArrays = Arrays.copyOf(grabArrays, len+1);
		grabArrays[len] = rga;
		
		grabClients = Arrays.copyOf(grabClients, len+1);
		grabClients[len] = client;
	    }
	}

	protected int haveClient(T client) {
	    synchronized(root) {
		for(int i=0;i<grabClients.length;i++) {
			if(grabClients[i] == client) return i;
		}
		return -1;
	    }
	}

	/**
	 * Get a grabber.
	 */
	@SuppressWarnings("unchecked")
	public C getGrabber(T client) {
	    synchronized(root) {
		int idx = haveClient(client);
		if(idx == -1) return null;
		else return (C)grabArrays[idx];
	    }
	}
	
	public T getClient(int x) {
	    synchronized(root) {
		return grabClients[x];
	    }
	}

	/**
	 * Put a grabber.
	 */
	public void addGrabber(T client, C requestGrabber, ClientContext context) {
	    synchronized(root) {
		if(requestGrabber.getObject() != client)
			throw new IllegalArgumentException("Client not equal to RemoveRandomWithObject's client: client="+client+" rr="+requestGrabber+" his object="+requestGrabber.getObject());
		addElement(client, requestGrabber);
		if(context != null) {
		    clearWakeupTime(context);
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
			RemoveRandomWithObject<T> rga = grabArrays[x];
			long excludeTime = rga.getWakeupTime(context, now);
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
		reduceWakeupTime(wakeupTime, context);
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
			RemoveRandomWithObject<T> rga = grabArrays[x];
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
			long excludeTime = rga.getWakeupTime(context, now);
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
		RemoveRandomWithObject<T> rga = grabArrays[x];
		RemoveRandomWithObject<T> firstRGA = rga;
		if(rga == null) {
			Logger.error(this, "rga = null on "+this);
			if(grabArrays[1-x] == null) {
				Logger.error(this, "other rga is also null on "+this);
				grabArrays = newGrabberArray(0);
				grabClients = newClientArray(0);
				return null;
			} else {
				Logger.error(this, "grabArrays["+(1-x)+"] is valid but ["+x+"] is null, correcting...");
				grabArrays = asGrabberArray(grabArrays[1-x]);
				grabClients = asClientArray(grabClients[1-x]);
				return null;
			}
		}
		RandomGrabArrayItem item = null;
		RemoveRandomReturn val = null;
		if(logMINOR) Logger.minor(this, "Only 2, trying "+rga);
		long excludeTime = rga.getWakeupTime(context, now);
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
				grabArrays = asGrabberArray(grabArrays[1-x]);
				grabClients = asClientArray(grabClients[1-x]);
                reduceWakeupTime(wakeupTime, context);
				return new RemoveRandomReturn(wakeupTime);
			}
			excludeTime = rga.getWakeupTime(context, now);
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
				grabArrays = newGrabberArray(0);
				grabClients = newClientArray(0);
			} else if(firstRGA != null && firstRGA.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Removing first: "+firstRGA+" is empty on "+this);
				grabArrays = asGrabberArray(grabArrays[x]); // don't use RGA, it may be nulled out
				grabClients = asClientArray(grabClients[x]);
			}
			if(logMINOR)
				Logger.minor(this, "Returning (two items only) "+item+" for "+rga);
			if(item == null) {
				if(grabArrays.length == 0)
					return null; // Remove this as well
                reduceWakeupTime(wakeupTime, context);
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
		RemoveRandomWithObject<T> rga = grabArrays[0];
		if(logMINOR) Logger.minor(this, "Only one RGA: "+rga);
		long excludeTime = rga.getWakeupTime(context, now);
		if(excludeTime > 0)
			return new RemoveRandomReturn(excludeTime);
		if(rga == null) {
			Logger.error(this, "Only one entry and that is null");
			// We are sure
			grabArrays = newGrabberArray(0);
			grabClients = newClientArray(0);
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
			grabArrays = newGrabberArray(0);
			grabClients = newClientArray(0);
		}
		if(logMINOR)
			Logger.minor(this, "Returning (one item only) "+item+" for "+rga);
		if(item == null) {
			if(grabArrays.length == 0) {
				if(logMINOR) Logger.minor(this, "Arrays are empty on "+this);
				return null; // Remove this as well
			}
            reduceWakeupTime(wakeupTime, context);
			return new RemoveRandomReturn(wakeupTime);
		} else return new RemoveRandomReturn(item);
	    }
	}

	private void removeElement(int x) {
	    synchronized(root) {
		final int grabArraysLength = grabArrays.length;
		int newLen = grabArraysLength > 1 ? grabArraysLength-1 : 0;
		RemoveRandomWithObject<T>[] newArray = newGrabberArray(newLen);
		if(x > 0)
			System.arraycopy(grabArrays, 0, newArray, 0, x);
		if(x < grabArraysLength-1)
			System.arraycopy(grabArrays, x+1, newArray, x, grabArraysLength - (x+1));
		grabArrays = newArray;
		
		T[] newClients = newClientArray(newLen);
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
		}
		if(finalSize == 0 && parent != null) {
			parent.maybeRemove(this, context);
		}
	}

	@Override
	public void setParent(RemoveRandomParent newParent) {
	    synchronized(root) {
		this.parent = newParent;
	    }
	}

    @Override
    public RequestSelectionTreeNode getParentGrabArray() {
        synchronized(root) {
            return parent;
        }
    }
	
    @Override
    public long getWakeupTime(ClientContext context, long now) {
        synchronized(root) {
            if(wakeupTime < now) wakeupTime = 0;
            return wakeupTime;
        }
    }
    
    @Override
    public boolean reduceWakeupTime(long wakeupTime, ClientContext context) {
        if(logMINOR) Logger.minor(this, "reduceCooldownTime("+(wakeupTime-System.currentTimeMillis())+") on "+this);
        boolean reachedRoot = false;
        synchronized(root) {
            if(this.wakeupTime > wakeupTime) {
                this.wakeupTime = wakeupTime;
                if(parent != null) parent.reduceWakeupTime(wakeupTime, context);
                else reachedRoot = true; // Even if it reduces it we need to wake it up.
            } else return false;
        }
        if(reachedRoot)
            root.wakeUp(context);
        return true;
    }
    
    @Override
    public void clearWakeupTime(ClientContext context) {
        if(logMINOR) Logger.minor(this, "clearCooldownTime() on "+this);
        synchronized(root) {
            wakeupTime = 0;
            if(parent != null) parent.clearWakeupTime(context);
        }
    }

    private T[] asClientArray(T client) {
        T[] clients = newClientArray(1);
        clients[0] = client;
        return clients;
    }

    @SuppressWarnings("unchecked")
    private T[] newClientArray(int length) {
        return (T[])new Object[length];
    }

    private RemoveRandomWithObject<T>[] asGrabberArray(RemoveRandomWithObject<T> grabber) {
        RemoveRandomWithObject<T>[] grabbers = newGrabberArray(1);
        grabbers[0] = grabber;
        return grabbers;
    }

    @SuppressWarnings("unchecked")
    private RemoveRandomWithObject<T>[] newGrabberArray(int length) {
        return (RemoveRandomWithObject<T>[])new RemoveRandomWithObject<?>[length];
    }
}
