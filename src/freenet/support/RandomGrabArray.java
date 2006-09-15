/*
  RandomGrabArray.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
			if(Logger.shouldLog(Logger.MINOR, this))
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
			if((index < reqs.length / 4) && (reqs.length > MIN_SIZE)) {
				// Shrink array
				int newSize = Math.max(index * 2, MIN_SIZE);
				RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
				System.arraycopy(reqs, 0, r, 0, r.length);
				reqs = r;
			}
			if((ret != null) && !ret.isFinished()) return ret;
		}
	}
	
	public synchronized void remove(RandomGrabArrayItem it) {
		if(!contents.contains(it)) return;
		contents.remove(it);
		for(int i=0;i<index;i++) {
			if((reqs[i] == it) || reqs[i].equals(it)) {
				reqs[i] = reqs[--index];
				reqs[index] = null;
				return;
			}
		}
	}

	public synchronized boolean isEmpty() {
		return index == 0;
	}
}
