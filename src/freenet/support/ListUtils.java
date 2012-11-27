/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;
import java.util.List;

public class ListUtils {
	/**
	 * Removes element from List by swapping with last element.
	 * O(n) comparison, O(1) moves.
	 * @return {@code true} if element was removed.
	 */
	public static <E> boolean removeBySwapLast(List<E> a, Object o) {
		int idx = a.indexOf(o);
		if (idx == -1)
			return false;
		int size = a.size();
		assert(size > 0); // always true
		E moved = a.remove(size-1);
		if (idx != size-1)
			a.set(idx, moved);
		return true;
	}
}

