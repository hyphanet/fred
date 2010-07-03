package freenet.support;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeSet;

public class SparseBitmap {
	private final TreeSet<Range> ranges;

	public SparseBitmap() {
		ranges = new TreeSet<Range>(new RangeComparator());
	}

	public void add(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Tried adding badd range. Start: " + start + ", end: " + end);
		}

		Iterator<Range> it = ranges.iterator();
		while(it.hasNext()) {
			Range range = it.next();
			if(range.start <= start && range.end >= end) {
				// Equal or inside
				return;
			} else if((range.start <= start && range.end >= start)
			                || (range.start <= end && range.end >= end)) {
				// Overlapping
				it.remove();

				Range newRange = new Range();
				newRange.start = Math.min(range.start, start);
				newRange.end = Math.max(range.end, end);
				ranges.add(newRange);
				return;
			}
		}
		ranges.add(new Range(start, end));
	}

	public void clear() {
		ranges.clear();
	}

	public boolean contains(int start, int end) {
		Iterator<Range> it = ranges.iterator();
		while(it.hasNext()) {
			Range r = it.next();
			if(r.start > r.end) Logger.error(this, "Bad Range: " + r);

			if((r.start <= start) && (r.end >= end)) {
				return true;
			}
		}
		return false;
	}

	public void remove(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Removing bad range. Start: " + start + ", end: " + end);
		}

		LinkedList<Range> toAdd = new LinkedList<Range>();

		Iterator<Range> it = ranges.iterator();
		while(it.hasNext()) {
			Range range = it.next();

			if(range.start < start) {
				if(range.end < start) {
					//Outside
					continue;
				} else if(range.end <= end) {
					//Overlaps beginning
					toAdd.add(new Range(range.start, start - 1));
				} else /* (range[1] > end) */{
					//Overlaps entire range
					toAdd.add(new Range(range.start, start - 1));
					toAdd.add(new Range(end + 1, range.end));
				}
			} else if(range.start >= start && range.start <= end) {
				if(range.end <= end) {
					// Equal or inside
					it.remove();
				} else /* (range[1] > end) */ {
					// Overlaps end
					toAdd.add(new Range(end + 1, range.end));
				}
			} else /* (range[0] > end) */ {
				//Outside
				continue;
			}
			it.remove();
		}

		ranges.addAll(toAdd);
	}

	private static class Range {
		int start; // inclusive
		int end;   // inclusive

		public Range() {}

		public Range(int start, int end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public String toString() {
			return "Range:"+start+"->"+end;
		}
	}

	private class RangeComparator implements Comparator<Range> {
		public int compare(Range r1, Range r2) {
			return r1.start - r2.start;
		}
	}
}
