package freenet.support;

import java.util.HashSet;

import freenet.crypt.RandomSource;

/**
 * An array which supports very fast remove-and-return-a-random-element.
 */
public class RandomGrabArray {

	/** Array of items. Non-null's followed by null's. */
	private RandomGrabArrayItem[] reqs;
	/** Index of first null item. */
	private int index;
	/** Random source */
	private RandomSource rand;
	/** What do we already have? FIXME: Replace with a Bloom filter or something (to save 
	 * RAM), or rewrite the whole class as a custom hashset maybe based on the classpath 
	 * HashSet. Note that removeRandom() is *the* common operation, so MUST BE FAST.
	 */
	private HashSet<RandomGrabArrayItem> contents;
	private final static int MIN_SIZE = 32;

	public RandomGrabArray(RandomSource rand) {
		this.reqs = new RandomGrabArrayItem[MIN_SIZE];
		index = 0;
		this.rand = rand;
		contents = new HashSet<RandomGrabArrayItem>();
	}
	
	public void add(RandomGrabArrayItem req) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(req.isEmpty()) {
			if(logMINOR) Logger.minor(this, "Is finished already: "+req);
			return;
		}
		req.setParentGrabArray(this);
		synchronized(this) {
			if(contents.contains(req)) {
				if(logMINOR) Logger.minor(this, "Already contains "+req+" : "+this+" size now "+index);
				return;
			}
			contents.add(req);
			if(index >= reqs.length) {
				RandomGrabArrayItem[] r = new RandomGrabArrayItem[reqs.length*2];
				System.arraycopy(reqs, 0, r, 0, reqs.length);
				reqs = r;
			}
			reqs[index++] = req;
			if(logMINOR) Logger.minor(this, "Added: "+req+" to "+this+" size now "+index);
		}
	}
	
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding) {
		RandomGrabArrayItem ret, oret;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(this) {
			final int MAX_EXCLUDED = 10;
			int excluded = 0;
			while(true) {
				if(index == 0) {
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
						for(int i=0;i<index;i++) {
							RandomGrabArrayItem item = reqs[i];
							if(item == null) {
								continue;
							} else if(item.isEmpty()) {
								reqs[i] = null;
								contents.remove(item);
								continue;
							}
							if(i != target) {
								reqs[i] = null;
								reqs[target] = item;
							}
							target++;
							if(excluding.exclude(item)) {
								exclude++;
							} else {
								if(valid == random) { // Picked on previous round
									chosenIndex = target-1;
								}
								validIndex = target-1;
								valid++;
							}
						}
						index = target;
						// We reach this point if 1) the random number we picked last round is invalid because an item became cancelled or excluded
						// or 2) we are on the first round anyway.
						if(chosenIndex >= 0) {
							ret = reqs[chosenIndex];
							if(ret.canRemove()) {
								contents.remove(ret);
								if(chosenIndex != index-1) {
									reqs[chosenIndex] = reqs[index-1];
								}
								index--;
								ret.setParentGrabArray(null);
							}
							if(logMINOR) Logger.minor(this, "Chosen random item "+ret+" out of "+valid);
							return ret;
						}
						if(valid == 0 && exclude == 0) {
							index = 0;
							if(logMINOR) Logger.minor(this, "No valid or excluded items");
							return null;
						} else if(valid == 0) {
							if(logMINOR) Logger.minor(this, "No valid items, "+exclude+" excluded items");
							return null;
						} else if(valid == 1) {
							ret = reqs[validIndex];
							if(ret.canRemove()) {
								contents.remove(ret);
								if(validIndex != index-1) {
									reqs[validIndex] = reqs[index-1];
								}
								index--;
								if(logMINOR) Logger.minor(this, "No valid or excluded items after removing "+ret);
								ret.setParentGrabArray(null);
							} else {
								if(logMINOR) Logger.minor(this, "No valid or excluded items apart from "+ret);
							}
							return ret;
						} else {
							random = rand.nextInt(valid);
						}
					}
				}
				int i = rand.nextInt(index);
				ret = reqs[i];
				if(ret == null) {
					Logger.error(this, "reqs["+i+"] = null");
					index--;
					if(i != index) {
						reqs[i] = reqs[index];
						reqs[index] = null;
					}
					continue;
				}
				oret = ret;
				if(ret.isEmpty()) {
					if(logMINOR) Logger.minor(this, "Not returning because cancelled: "+ret);
					ret = null;
				}
				if(ret != null && excluding.exclude(ret)) {
					excluded++;
					if(excluded > MAX_EXCLUDED) {
						Logger.error(this, "Remove random returning null because "+excluded+" excluded items, length = "+index, new Exception("error"));
						return null;
					}
					continue;
				}
				if(ret != null && !ret.canRemove()) {
					if(logMINOR) Logger.minor(this, "Returning (cannot remove): "+ret+" of "+index);
					return ret;
				}
				do {
					reqs[i] = reqs[--index];
					reqs[index] = null;
					if(oret != null)
						contents.remove(oret);
					oret = reqs[i];
					// May as well check whether that is cancelled too.
				} while (index > i && (oret == null || oret.isEmpty()));
				// Shrink array
				if((index < reqs.length / 4) && (reqs.length > MIN_SIZE)) {
					// Shrink array
					int newSize = Math.max(index * 2, MIN_SIZE);
					RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
					System.arraycopy(reqs, 0, r, 0, r.length);
					reqs = r;
				}
				if((ret != null) && !ret.isEmpty()) break;
			}
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret+" of "+index);
		ret.setParentGrabArray(null);
		return ret;
	}
	
	public void remove(RandomGrabArrayItem it) {
		synchronized(this) {
			if(!contents.contains(it)) return;
			contents.remove(it);
			for(int i=0;i<index;i++) {
				if(reqs[i] == null) continue;
				if((reqs[i] == it) || reqs[i].equals(it)) {
					reqs[i] = reqs[--index];
					reqs[index] = null;
					break;
				}
			}
		}
		it.setParentGrabArray(null);
	}

	public synchronized boolean isEmpty() {
		return index == 0;
	}
}
