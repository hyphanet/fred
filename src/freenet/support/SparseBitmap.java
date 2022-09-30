package freenet.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

public class SparseBitmap implements Iterable<int[]> {
	// Ranges ordered by start time. Invariant: ranges do not overlap and do not touch.
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

	/**
	 * Marks the slots between start and end (inclusive) as present.
	 */
	public void add(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Tried adding bad range. Start: " + start + ", end: " + end);
		}
		NavigableSet<Range> toReplace = overlaps(start, end, true);
		if (!toReplace.isEmpty()) {
			Range first = toReplace.first();
			if (first.start < start) {
				start = first.start;
			}
			Range last = toReplace.last();
			if (last.end > end) {
				end = last.end;
			}
			toReplace.clear();
		}
		ranges.add(new Range(start, end));
	}

	public void clear() {
		ranges.clear();
	}

	/**
	 * Checks whether all slots between start and end (inclusive) are present.
	 */
	public boolean contains(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Tried checking bad range. Start: " + start + ", end: " + end);
		}

		// Find the latest range starting before (or at) start, if any exists.
		Range floor = ranges.floor(new Range(start, end));
		// By definition of floor(), lower.start <= start.
		return floor != null && floor.end >= end;
	}

	/**
	 * Marks all slots between start and end (inclusive) as not present.
	 */
	public void remove(int start, int end) {
		if(start > end) {
			throw new IllegalArgumentException("Removing bad range. Start: " + start + ", end: " + end);
		}

		List<Range> toAdd = new ArrayList<Range>();

		Iterator<Range> it = overlaps(start, end, false).iterator();
		while (it.hasNext()) {
			Range range = it.next();

			if (range.start < start) {
				if (range.end <= end) {
					//Overlaps beginning
					toAdd.add(new Range(range.start, start - 1));
				} else if (range.end > end) {
					//Overlaps entire range
					toAdd.add(new Range(range.start, start - 1));
					toAdd.add(new Range(end + 1, range.end));
				}
			} else {
				if (range.end > end) {
					// Overlaps end
					toAdd.add(new Range(end + 1, range.end));
				}
				//Else it is equal or inside
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

	/** Finds all ranges that overlap or touch the given range. */
	private NavigableSet<Range> overlaps(int start, int end, boolean includeTouching) {
		// Establish bounds on start times to select ranges that would overlap or touch
		Range startRange = new Range(start, 0);
		Range lower = ranges.lower(startRange);
		if (lower != null && lower.end >= (includeTouching ? start - 1 : start)) {
			// This range would overlap (or touch, if those are to be included)
			startRange = new Range(lower.start, 0);
		}
		Range endRange = new Range(end + 1, 0);
		// Any range with start time within startRange and endRange would touch or overlap
		return ranges.subSet(startRange, true, endRange, includeTouching);
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
		final int start; // inclusive
		final int end;   // inclusive

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
		int count = end - start + 1;
		for (Range range : overlaps(start, end, false)) {
			if (range.end < start || range.start > end) {
				throw new IllegalStateException();
			}
			int overlap = range.end - range.start + 1;
			if (range.start < start) {
				overlap -= start - range.start;
			}
			if (range.end > end) {
				overlap -= range.end - end;
			}
			count -= overlap;
		}
		return count;
	}
}
