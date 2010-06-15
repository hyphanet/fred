package freenet.support;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.SortedSet;

public class SparseBitmap {
	final LinkedList<Range> ranges;

	public SparseBitmap() {
		ranges = new LinkedList<Range>();
	}

	public void add(int start, int end) {
		throw new UnsupportedOperationException();
	}

	public boolean contains(int index) {
		Iterator<Range> it = ranges.iterator();
		while(it.hasNext()) {
			Range r = it.next();
			if(r.start > r.end) Logger.error(this, "Bad Range: " + r);

			if((r.start <= index) && (r.end >= index)) {
				return true;
			}
		}
		return false;
	}

	public void clear() {
		ranges.clear();
	}

	private static class Range {
		int start; // inclusive
		int end;   // inclusive

		@Override
		public String toString() {
			return "Range:"+start+"->"+end;
		}
	}

	private void addRangeToSet(int start, int end, SortedSet<int[]> set) {
		if(start > end) {
			Logger.error(this, "Adding bad range. Start: " + start + ", end: " + end, new Exception());
			return;
		}

		synchronized(set) {
			Iterator<int[]> it = set.iterator();
			while (it.hasNext()) {
				int[] range = it.next();
				if(range[0] >= start && range[1] <= end) {
					// Equal or inside
					return;
				} else if((range[0] <= start && range[1] >= start)
				                || (range[0] <= end && range[1] >= end)) {
					// Overlapping
					it.remove();

					int[] newRange = new int[2];
					newRange[0] = Math.min(range[0], start);
					newRange[1] = Math.max(range[1], end);
					set.add(newRange);
					return;
				}
			}
		}
	}

	private void removeRangeFromSet(int start, int end, SortedSet<int[]> set) {
		if(start > end) {
			Logger.error(this, "Removing bad range. Start: " + start + ", end: " + end, new Exception());
			return;
		}

		synchronized(set) {
			LinkedList<int[]> toAdd = new LinkedList<int[]>();

			Iterator<int[]> it = set.iterator();
			while (it.hasNext()) {
				int[] range = it.next();

				if(range[0] < start) {
					if(range[1] < start) {
						//Outside
						continue;
					} else if(range[1] <= end) {
						//Overlaps beginning
						toAdd.add(new int [] {range[0], start - 1});
					} else /* (range[1] > end) */{
						//Overlaps entire range
						toAdd.add(new int [] {range[0], start - 1});
						toAdd.add(new int [] {end + 1, range[1]});
					}
				} else if(range[0] >= start && range[0] <= end) {
					if (range[1] <= end) {
						// Equal or inside
						it.remove();
					} else /* (range[1] > end) */ {
						// Overlaps end
						toAdd.add(new int [] {end + 1, range[1]});
					}
				} else /* (range[0] > end) */ {
					//Outside
					continue;
				}
				it.remove();
			}

			set.addAll(toAdd);
		}
	}
}
