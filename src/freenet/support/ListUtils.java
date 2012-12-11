/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;
import java.util.List;

public class ListUtils {
	/**
	 * Removes element from List by swapping with last element.
	 * O(n) comparison, O(1) moves.
	 * This method is useful with ArrayList-alike containers
	 * with O(1) get(index) and O(n) remove(index)
	 * when keeping array order is not important.
	 * Not synchronized (even with synchronized containers).
	 * @return {@code true} if element was removed.
	 */
	public static <E> boolean removeBySwapLast(List<E> a, Object o) {
		int idx = a.indexOf(o);
		if (idx == -1)
			return false;
		removeBySwapLast(a, idx);
		return true;
	}

	/**
	 * Removes element by index from List by swapping with last element.
	 * 0 comparison, O(1) moves.
	 * This method is useful with ArrayList-alike containers
	 * with O(1) get(index) and O(n) remove(index)
	 * when keeping array order is not important.
	 * Not synchronized (even with synchronized containers!).
	 * @return moved element that will replace current index or
	 * removed element if it was last element (and nothing was moved).
	 * WARNING: returned result is DIFFERENT from List.remove(index)!
	 * (this is intentional to allow useful optimizations).
	 * @throws IndexOutOfBoundsException if idx is not valid index
	 * WARNING: Don't dare to break this method contract!
	 */
	public static <E> E removeBySwapLast(List<E> a, int idx) {
		int size = a.size();
		if (idx < 0 || idx >= size) throw new IndexOutOfBoundsException(idx+" out of range [0;"+size+")");
		E moved = a.remove(size-1);
		if (idx != size-1)
			a.set(idx, moved);
		return moved;
	}
}

