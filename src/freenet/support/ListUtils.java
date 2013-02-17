/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;
import java.util.List;
import java.util.Random;

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

	public static class RandomRemoveResult<E> {
		public final E removed;
		public final E moved;
		RandomRemoveResult(E removed, E moved) {
			this.removed = removed;
			this.moved = moved;
		}
	}

	/**
	 * Removes random element from List by swapping with last element.
	 * O(1) moves.
	 * This method is useful with ArrayList-alike containers
	 * with O(1) get(index) and O(n) remove(index)
	 * when keeping array order is not important.
	 * Not synchronized (even with synchronized containers!).
	 * WARNING: amount of fetched random data is implementation-defined
	 * @return null if list is empty, otherwise RandomRemoveResult(removed_element, moved_element)
	 */
	public static <E> RandomRemoveResult<E> removeRandomBySwapLast(Random random, List<E> a) {
		int size = a.size();
		if (size == 0) return null;
		if (size == 1) {
			// short-circuit, avoid expensive random call
			E removed = a.remove(0);
			return new RandomRemoveResult<E>(removed, removed);
		}
		int idx = random.nextInt(size);
		E removed = a.get(idx);
		return new RandomRemoveResult<E>(removed, removeBySwapLast(a, idx));
	}
	/**
	 * Removes random element from List by swapping with last element.
	 * O(1) moves.
	 * This method is useful with ArrayList-alike containers
	 * with O(1) get(index) and O(n) remove(index)
	 * when keeping array order is not important.
	 * Not synchronized (even with synchronized containers!).
	 * WARNING: amount of fetched random data is implementation-defined
	 * @return null if list is empty, removed element otherwise
	 */
	public static <E> E removeRandomBySwapLastSimple(Random random, List<E> a) {
		int size = a.size();
		if (size == 0) return null;
		if (size == 1) {
			// short-circuit, avoid expensive random call
			return a.remove(0);
		}
		int idx = random.nextInt(size);
		E removed = a.get(idx);
		removeBySwapLast(a, idx);
		return removed;
	}
}

