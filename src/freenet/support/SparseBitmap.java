package freenet.support;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.LinkedList;

/**
 * Bitmap class containing ranges of marked bits.
 */
public class SparseBitmap implements Iterable<int[]> {
	private final LinkedList<Range> ranges;

	/**
	 * Construct an empty SparseBitmap.
	 */
	public SparseBitmap() {
		ranges = new LinkedList<Range>();
	}

	/**
	 * Constructs a clone of the given SparseBitmap, containing all its ranges.
	 */
	public SparseBitmap(SparseBitmap original) {
		ranges = new LinkedList<Range>();

		for(Range range : original.ranges) {
			ranges.add(new Range(range));
		}
	}

	/**
	 * Adds the given range to this SparseBitmap.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 */
	public void add(int start, int end) {
		validateRange(start, end);
		
		ListIterator<Range> it = ranges.listIterator();
		Range adjust = null;
		while (it.hasNext()) {
			adjust = it.next();
			if (adjust.end < start - 1) {
				adjust = null;
				continue;
			}
			it.previous();
			break;
		}
		if (adjust != null && adjust.start <= end + 1) {
			// There is some overlap, adjust range
			adjust.start = Math.min(adjust.start, start);
			adjust.end = Math.max(adjust.end, end);
			it.next();
			
			// Remove all conflicting ranges and extend our range accordingly
			while (it.hasNext())
			{
				Range r = it.next();
				if (adjust.end >= r.start - 1) {
					adjust.end = Math.max(adjust.end, r.end);
					it.remove();
				} else {
					return;
				}
			}
		} else {
			// No overlap, insert here
			it.add(new Range(start, end));
		}
	}

	/**
	 * Removes the given range from this SparseBitmap.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 */
	public void remove(int start, int end) {
		validateRange(start, end);
		
		ListIterator<Range> it = ranges.listIterator();
		while (it.hasNext()) {
			Range r = it.next();
			if (r.end < start) {
				continue;
			}
			if (r.start < start) {
				if (r.end > end) {
					// Split: add right part
					it.add(new Range(end + 1, r.end));
				}
				// Adjust left overlap
				r.end = start - 1;
			} else if (r.start <= end) {
				if (r.end > end) {
					// Adjust right overlap
					r.start = end + 1;
					return;
				}
				// Range is entirely within [start, end]
				it.remove();
			} else {
				return;
			}
		}
	}

	/**
	 * Removes all ranges from this SparseBitmap.
	 */
	public void clear() {
		ranges.clear();
	}

	/**
	 * Tests whether this SparseBitmap entirely contains the given range.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 * @return true if the entire range is in this bitmap, false otherwise
	 */
	public boolean contains(int start, int end) {
		validateRange(start, end);

		for (Range r : ranges) {
			if (r.end >= end) {
				return r.start <= start;
			}
		}
		return false;
	}

	/**
	 * Calculates the number of slots in the given range that are not in this SpareBitmap.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 * @return the number of slots between start and end that are not marked as present
	 */
	public int notOverlapping(int start, int end) {
		validateRange(start, end);
	
		int remaining = end - start + 1;
		for (Range r : ranges) {
			if (r.end < start) {
				// r not in [start, end]
				continue;
			}
			if (r.start < start) {
				if (r.end >= end) {
					// [start, end] is fully within r
					return 0;
				}
				// r touches [start, end] at the left
				remaining -= r.end - start + 1;
			}
			else if (r.end > end) {
				if (r.start <= end) {
					// r touches [start, end] at the right
					remaining -= end - r.start + 1;
				}
				break;
			} else {
				// r is fully within [start, end]
				remaining -= r.end - r.start + 1;
			}
		}
		return remaining;
	}

	/**
	 * Yields an iterator over this SparseBitmap.
	 * @return an iterator over the ranges contained in this SparseBitmap
	 */
	@Override
	public Iterator<int[]> iterator() {
		return new SparseBitmapIterator(this);
	}

	/**
	 * Tests whether this SparseBitmap is empty.
	 * @return true if this SparseBitmap contains no ranges, false otherwise.
	 */
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

	/* Iterator over SparseBitmaps */
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

	/* Container class for simple ranges with start and end indices, both inclusive */
	private static class Range {
		int start; // inclusive
		int end;   // inclusive

		public Range(int start, int end) {
			this.start = start;
			this.end = end;
		}

		public Range(Range other) {
			this.start = other.start;
			this.end = other.end;
		}

		@Override
		public String toString() {
			return "Range:"+start+"->"+end;
		}
	}
	
	/* Helper for validating start and end indices (e.g. start <= end) */
	private void validateRange(int start, int end) {
		if (start > end) {
			throw new IllegalArgumentException(String.format("Invalid range: start=%d, end=%d", start, end));
		}
	}	
}

