package freenet.support;

import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * Bitmap class containing ranges of marked bits.
 */
public class SparseBitmap implements Iterable<int[]> {
	private final TreeSet<Range> ranges;

	/**
	 * Construct an empty SparseBitmap.
	 */
	public SparseBitmap() {
		ranges = new TreeSet<Range>(new RangeStartComparator());
	}

	/**
	 * Constructs a clone of the given SparseBitmap, containing all its ranges.
	 */
	public SparseBitmap(SparseBitmap original) {
		ranges = new TreeSet<Range>(new RangeStartComparator());

		for(int[] range : original) {
			add(range[0], range[1]);
		}
	}

	/**
	 * Adds the given range to this SparseBitmap.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 */
	public void add(int start, int end) {
		validateRange(start, end);
		
		if (!ranges.isEmpty()) {
			// Query for the highest existing range, starting at our start position or before
			Range lowerQuery = new Range(start, 0);
			Range lower = ranges.floor(lowerQuery);
			if (lower != null) {
				if (lower.end >= end) {
					// The range to add is inside an existing range, nothing to do
					return;
				}
				if (lower.end >= start - 1) {
					// Overlapping/appending at our start, extend range to add
					// Note: lower.start <= start by definition of floor() and lowerQuery
					start = lower.start;
				}
			}
			
			// Query for the highest existing range, starting directly after our end position or before
			Range higherQuery = new Range(end + 1, 0);
			Range higher = ranges.floor(higherQuery);
			if (higher != null && higher.end > end) {
				// Overlapping/appending at our end, extend range to add
				end = higher.end;
			}
			
			// Remove all ranges inside our range to add
			lowerQuery.start = start;
			higherQuery.start = end;
			removeSubSet(lowerQuery, higherQuery);
		}		

		ranges.add(new Range(start, end));
	}

	/**
	 * Removes the given range from this SparseBitmap.
	 * @param start the start index, inclusive
	 * @param end   the end index, inclusive, greater than or equal to the start index
	 */
	public void remove(int start, int end) {
		validateRange(start, end);
		
		if (ranges.isEmpty()) {
			return;
		}
		
		// Adjust overlapping Range at start
		Range lowerQuery = new Range(start, 0);
		Range strictlyLower = ranges.lower(lowerQuery);
		if (strictlyLower != null && strictlyLower.end >= start) {
			// Since the Ranges are ordered by start index, and Ranges are not exposed
			// externally, we can safely adjust the end index in place
			// Note: we never remove strictlyLower, since lower() guarantees
			// strictlyLower.start <= start - 1, hence it will be non-empty.
			if (strictlyLower.end > end) {
				// The range to remove is entirely inside an existing range
				ranges.add(new Range(end + 1, strictlyLower.end));
				strictlyLower.end = start - 1;
				return;
			} else {
				strictlyLower.end = start - 1;
			}
		}
		
		// Adjust overlapping Range at end
		Range higherQuery = new Range(end, 0);
		Range higher = ranges.floor(higherQuery);
		if (higher != null && higher.end > end) {
			// Replace higher with an adjusted version
			ranges.remove(higher);
			higher.start = end + 1;
			ranges.add(higher);
		}
		
		// Remove everything in between
		removeSubSet(lowerQuery, higherQuery);
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

		if (ranges.isEmpty()) {
			return false;
		}

		Range lower = ranges.floor(new Range(start, 0));
		// Note: lower != null implies lower.start <= start, by definition of floor()
		return lower != null && lower.end >= end;
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
		if (ranges.isEmpty()) {
			return remaining;
		}
	
		Range query = new Range(start, 0);
		// Special case for the first overlap
		Range strictlyLower = ranges.lower(query);
		if (strictlyLower != null && strictlyLower.end >= start) {
			if (strictlyLower.end >= end) {
				return 0;
			}
			remaining -= strictlyLower.end - start + 1;
		}
		for (Range r : ranges.tailSet(query)) {
			if (r.end > end) {
				// Special case for the last overlap
				if (r.start <= end) {
					remaining -= end - r.start + 1;
				}
				break;
			} else {
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

		@Override
		public String toString() {
			return "Range:"+start+"->"+end;
		}
	}

	/* Compares Ranges based on their start index */
	private static class RangeStartComparator implements Comparator<Range> {
		@Override
		public int compare(Range r1, Range r2) {
			return r1.start - r2.start;
		}
	}

	/* Helper for validating start and end indices (e.g. start <= end) */
	private void validateRange(int start, int end) {
		if (start > end) {
			throw new IllegalArgumentException(String.format("Invalid range: start=%d, end=%d", start, end));
		}
	}
	
	 /* Helper for low-level removal of all Ranges r with lQ.start <= r.start <= hQ.start */
	private void removeSubSet(Range lowerQuery, Range higherQuery) {
		Range lower = ranges.ceiling(lowerQuery);
		if (lower == null) {
			return;
		}
		Range upper = ranges.floor(higherQuery);
		if (upper == null) {
			return;
		}
		if (upper == lower) {
			ranges.remove(upper);
			return;
		}
		// Fallback to linear algorithm, subSets are too expensive for small SparseBitmaps.
		Iterator<Range> it = ranges.iterator();
		while (it.hasNext()) {
			Range r = it.next();
			if (r.start < lowerQuery.start) {
				continue;
			}
			if (r.start > higherQuery.start) {
				break;
			}
			it.remove();
		}
	}
}

