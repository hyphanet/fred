package freenet.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

public class SparseBitmap implements Iterable<int[]> {
	private final TreeSet<Range> ranges;

	public SparseBitmap() {
		ranges = new TreeSet<Range>(new RangeComparator());
	}

	public SparseBitmap(SparseBitmap original) {
		ranges = new TreeSet<Range>(new RangeComparator());

		for(int[] range : original) {
			add(range[0], range[1]);
		}
	}

	public void add(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Tried adding bad range. Start: " + start + ", end: " + end);
		}

		Iterator<Range> it = ranges.iterator();
		while(it.hasNext()) {
			Range range = it.next();
			if(range.start <= start && range.end >= end) {
				// Equal or inside
				return;
			} else if((range.start <= start && range.end >= (start - 1))
			                || (range.start <= (end + 1) && range.end >= end)) {
				// Overlapping
				it.remove();

				start = Math.min(start, range.start);
				end = Math.max(end, range.end);
			}
		}
		ranges.add(new Range(start, end));
	}

	public void clear() {
		ranges.clear();
	}

	public boolean contains(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Tried checking bad range. Start: " + start + ", end: " + end);
		}

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

		List<Range> toAdd = new ArrayList<Range>();

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
				if(range.end > end) {
					// Overlaps end
					toAdd.add(new Range(end + 1, range.end));
				}
				//Else it is equal or inside
			} else /* (range[0] > end) */ {
				//Outside
				continue;
			}
			it.remove();
		}

		ranges.addAll(toAdd);
	}

	@Override
	public Iterator<int[]> iterator() {
		return new SparseBitmapIterator(this);
	}

	public boolean isEmpty() {
		return ranges.isEmpty();
	}

	@Override
	public String toString() {
		StringBuffer s = new StringBuffer();
		for(int[] range : this) {
			if(s.length() != 0) s.append(", ");
			s.append(range[0] + "->" + range[1]);
		}
		return s.toString();
	}

	private static class SparseBitmapIterator implements Iterator<int[]> {
		Iterator<Range> it;

		public SparseBitmapIterator(SparseBitmap map) {
			it = map.ranges.iterator();
		}

		@Override
		public boolean hasNext() {
			return it.hasNext();
		}

		@Override
		public int[] next() {
			Range r = it.next();
			return new int[] {r.start, r.end};
		}

		@Override
		public void remove() {
			it.remove();
		}
	}

	private static class Range {
		int start; // inclusive
		int end;   // inclusive

		public Range(int start, int end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public String toString() {
			return "Range:"+start+"->"+end;
		}
	}

	private static class RangeComparator implements Comparator<Range> {
		@Override
		public int compare(Range r1, Range r2) {
			return r1.start - r2.start;
		}
	}

	/** @return The number of slots between start and end that are not marked as present */
	public int notOverlapping(int start, int end) {
		// FIXME OPTIMIZE: this is an incredibly stupid and inefficient but demonstrably correct way to evaluate this. Implement something better!
		int total = 0;
		for(int i=start;i<=end;i++) {
			if(!contains(i, i))
				total++;
		}
		return total;
	}
}
