package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

/**
 * An array which supports very fast remove-and-return-a-random-element.
 */
public class RandomGrabArray {

	/** Array of items. Non-null's followed by null's. 
	 * We used to have a Set so we could check whether something is in the set quickly.
	 * We got rid of this because for persistent requests it is vastly faster to just loop the
	 * loop and check ==, and for non-persistent requests it doesn't matter much. */
	private RandomGrabArrayItem[] reqs;
	/** Index of first null item. */
	private int index;
	private final static int MIN_SIZE = 32;
	private final boolean persistent;

	public RandomGrabArray(boolean persistent, ObjectContainer container) {
		this.reqs = new RandomGrabArrayItem[MIN_SIZE];
		this.persistent = persistent;
		index = 0;
	}
	
	public void add(RandomGrabArrayItem req, ObjectContainer container) {
		if(req.persistent() != persistent) throw new IllegalArgumentException("req.persistent()="+req.persistent()+" but array.persistent="+persistent+" item="+req+" array="+this);
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(req.isEmpty(container)) {
			if(logMINOR) Logger.minor(this, "Is finished already: "+req);
			return;
		}
		req.setParentGrabArray(this, container);
		synchronized(this) {
			for(int i=0;i<index;i++) {
				if(reqs[i] == req) {
					if(logMINOR) Logger.minor(this, "Already contains "+req+" : "+this+" size now "+index);
					return;
				}
				if(reqs[i] == null) {
					Logger.error(this, "reqs["+i+"] = null on "+this);
				}
			}
			if(index >= reqs.length) {
				RandomGrabArrayItem[] r = new RandomGrabArrayItem[reqs.length*2];
				System.arraycopy(reqs, 0, r, 0, reqs.length);
				reqs = r;
			}
			reqs[index++] = req;
			if(logMINOR) Logger.minor(this, "Added: "+req+" to "+this+" size now "+index);
			if(persistent) {
				container.store(this);
			}
		}
	}
	
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context) {
		RandomGrabArrayItem ret, oret;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "removeRandom() on "+this+" index="+index);
		synchronized(this) {
			final int MAX_EXCLUDED = 10;
			int excluded = 0;
			boolean changedMe = false;
			while(true) {
				if(index == 0) {
					if(reqs == null)
						throw new NullPointerException();
					if(logMINOR) Logger.minor(this, "All null on "+this);
					return null;
				}
				if(index < MAX_EXCLUDED) {
					// Optimise the common case of not many items, and avoid some spurious errors.
					int random = -1;
					while(true) {
						int exclude = 0;
						int valid = 0;
						int validIndex = -1;
						int target = 0;
						int chosenIndex = -1;
						RandomGrabArrayItem chosenItem = null;
						RandomGrabArrayItem validItem = null;
						for(int i=0;i<index;i++) {
							// Compact the array.
							RandomGrabArrayItem item = reqs[i];
							if(persistent)
								container.activate(item, 1);
							if(item == null) {
								continue;
							} else if(item.isEmpty(container)) {
								changedMe = true;
								// We are doing compaction here. We don't need to swap with the end; we write valid ones to the target location.
								reqs[i] = null;
								item.setParentGrabArray(null, container);
								if(persistent)
									container.deactivate(item, 1);
								continue;
							}
							if(i != target) {
								changedMe = true;
								reqs[i] = null;
								reqs[target] = item;
							} // else the request can happily stay where it is
							target++;
							if(excluding.exclude(item, container, context)) {
								exclude++;
							} else {
								if(valid == random) { // Picked on previous round
									chosenIndex = target-1;
									chosenItem = item;
								}
								if(validIndex == -1) {
									// Take the first valid item
									validIndex = target-1;
									validItem = item;
								}
								valid++;
							}
							if(persistent && item != chosenItem && item != validItem) {
								if(logMINOR)
									Logger.minor(this, "Deactivating "+item);
								container.deactivate(item, 1);
								if(container.ext().isActive(item))
									Logger.error(this, "Still active after deactivation: "+item);
								else if(logMINOR)
									Logger.minor(this, "Deactivated: "+item);
							}
						}
						if(index != target) {
							changedMe = true;
							index = target;
						}
						// We reach this point if 1) the random number we picked last round is invalid because an item became cancelled or excluded
						// or 2) we are on the first round anyway.
						if(chosenItem != null) {
							if(persistent && validItem != null && validItem != chosenItem)
								container.deactivate(validItem, 1);
							changedMe = true;
							ret = chosenItem;
							assert(ret == reqs[chosenIndex]);
							if(logMINOR) Logger.minor(this, "Chosen random item "+ret+" out of "+valid+" total "+index);
							if(persistent && changedMe)
								container.store(this);
							return ret;
						}
						if(valid == 0 && exclude == 0) {
							index = 0;
							if(persistent)
								container.store(this);
							if(logMINOR) Logger.minor(this, "No valid or excluded items total "+index);
							return null;
						} else if(valid == 0) {
							if(persistent && changedMe)
								container.store(this);
							if(logMINOR) Logger.minor(this, "No valid items, "+exclude+" excluded items total "+index);
							return null;
						} else if(valid == 1) {
							ret = validItem;
							assert(ret == reqs[validIndex]);
							if(logMINOR) Logger.minor(this, "No valid or excluded items apart from "+ret+" total "+index);
							if(persistent && changedMe)
								container.store(this);
							return ret;
						} else {
							random = context.fastWeakRandom.nextInt(valid);
						}
					}
				}
				int i = context.fastWeakRandom.nextInt(index);
				ret = reqs[i];
				if(ret == null) {
					Logger.error(this, "reqs["+i+"] = null");
					index--;
					if(i != index) {
						reqs[i] = reqs[index];
						reqs[index] = null;
					}
					changedMe = true;
					continue;
				}
				if(persistent)
					container.activate(ret, 1);
				oret = ret;
				if(ret.isEmpty(container)) {
					if(logMINOR) Logger.minor(this, "Not returning because cancelled: "+ret);
					ret = null;
					// Will be removed in the do{} loop
					// Tell it that it's been removed first.
					oret.setParentGrabArray(null, container);
				}
				if(ret != null && excluding.exclude(ret, container, context)) {
					excluded++;
					if(persistent)
						container.deactivate(ret, 1);
					if(excluded > MAX_EXCLUDED) {
						Logger.normal(this, "Remove random returning null because "+excluded+" excluded items, length = "+index, new Exception("error"));
						if(persistent && changedMe)
							container.store(this);
						return null;
					}
					continue;
				}
				if(ret != null) {
					if(logMINOR) Logger.minor(this, "Returning (cannot remove): "+ret+" of "+index);
					if(persistent && changedMe)
						container.store(this);
					return ret;
				}
				// Remove an element.
				do {
					changedMe = true;
					reqs[i] = reqs[--index];
					reqs[index] = null;
					if(persistent && oret != null && ret == null) // if ret != null we will return it
						container.deactivate(oret, 1);
					oret = reqs[i];
					// Check for nulls, but don't check for cancelled, since we'd have to activate.
				} while (index > i && oret == null);
				// Shrink array
				if((index < reqs.length / 4) && (reqs.length > MIN_SIZE)) {
					changedMe = true;
					// Shrink array
					int newSize = Math.max(index * 2, MIN_SIZE);
					RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
					System.arraycopy(reqs, 0, r, 0, r.length);
					reqs = r;
				}
				if(ret != null) break;
			}
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret+" of "+index);
		ret.setParentGrabArray(null, container);
		if(persistent)
			container.store(this);
		return ret;
	}
	
	public void remove(RandomGrabArrayItem it, ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Removing "+it+" from "+this);
		synchronized(this) {
			for(int i=0;i<index;i++) {
				if(reqs[i] == null) continue;
				if((reqs[i] == it) || reqs[i].equals(it)) {
					reqs[i] = reqs[--index];
					reqs[index] = null;
					break;
				}
			}
		}
		it.setParentGrabArray(null, container);
		if(persistent) {
			container.store(this);
		}
	}

	public synchronized boolean isEmpty() {
		return index == 0;
	}
	
	public boolean persistent() {
		return persistent;
	}

	public boolean contains(RandomGrabArrayItem item, ObjectContainer container) {
		for(int i=0;i<index;i++) {
			if(reqs[i] == item) return true;
		}
		return false;
	}
	
	public synchronized int size() {
		return index;
	}

	public synchronized RandomGrabArrayItem get(int idx) {
		return reqs[idx];
	}
}
