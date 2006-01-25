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
	private HashSet contents;
	private final static int MIN_SIZE = 32;

	public RandomGrabArray(RandomSource rand) {
		this.reqs = new RandomGrabArrayItem[MIN_SIZE];
		index = 0;
		this.rand = rand;
		contents = new HashSet();
	}
	
	public synchronized void add(RandomGrabArrayItem req) {
		if(contents.contains(req)) return;
		if(req.isFinished()) {
			Logger.minor(this, "Is finished already: "+req);
			return;
		}
		contents.add(req);
		if(index >= reqs.length) {
			RandomGrabArrayItem[] r = new RandomGrabArrayItem[reqs.length*2];
			System.arraycopy(reqs, 0, r, 0, reqs.length);
			reqs = r;
		}
		reqs[index++] = req;
	}
	
	public synchronized RandomGrabArrayItem removeRandom() {
		while(true) {
			if(index == 0) return null;
			int i = rand.nextInt(index);
			RandomGrabArrayItem ret = reqs[i];
			reqs[i] = reqs[--index];
			reqs[index] = null;
			if(ret != null)
				contents.remove(ret);
			// Shrink array
			if(index < reqs.length / 4 && reqs.length > MIN_SIZE) {
				// Shrink array
				int newSize = Math.max(index * 2, MIN_SIZE);
				RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
				System.arraycopy(reqs, 0, r, 0, r.length);
				reqs = r;
			}
			if(ret != null && !ret.isFinished()) return ret;
		}
	}
	
	public synchronized void remove(RandomGrabArrayItem it) {
		if(!contents.contains(it)) return;
		contents.remove(it);
		for(int i=0;i<index;i++) {
			if(reqs[i] == it || reqs[i].equals(it)) {
				reqs[i] = reqs[--index];
				reqs[index] = null;
				return;
			}
		}
	}

	public boolean isEmpty() {
		return index == 0;
	}
}
