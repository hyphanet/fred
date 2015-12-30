package freenet.support;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * Reusable read-only iterator with randomized array iteration order.
 *
 * This iterator iterates over the underlying array, yielding its elements in an (optionally)
 * randomized order. Instances can be reused by {@link #reset(Random) resetting} them to their
 * original position, optionally yielding a new random permutation of the array elements.
 *
 * Storage requirements are linear in the size of the underlying array, all instance operations
 * finish in constant time.
 *
 * Instances of this class are not thread-safe.
 *
 * @author bertm
 */
public class RandomArrayIterator<E> implements Iterator<E> {
    /** The underlying array. */
    private final E[] array;

    /** Permutation state. This array contains a permutation of indices into {@link #array}. */
    private final int[] indices;
    /** Random source for the current run. */
    private Random random;
    /** Current position in indices array. */
    private int i;

    /**
     * Creates a new randomized iterator for the given array.
     * @param array The underlying array
     * @param random Random source for the iteration order
     */
    public RandomArrayIterator(E[] array, Random random) {
        this.array = array;
        this.indices = sequence(array.length);
        reset(random);
    }

    /**
     * Creates a new iterator for the given array. Initially, the array is iterated in the original
     * ordering, {@link #reset(Random)} the iterator to get a new random iteration ordering.
     * @param array The underlying array
     */
    public RandomArrayIterator(E[] array) {
        this(array, null);
    }

    /**
     * Resets this iterator. If a random source is given, the next sequence of calls to
     * {@link #next()} will iterate over the array in a new random order. If it is {@code null},
     * the iteration order remains unaltered.
     * @param random Random source for the next run, or {@code null} to repeat the previous run
     */
    public void reset(Random random) {
        this.random = random;
        i = 0;
    }

    @Override
    public boolean hasNext() {
        return i < indices.length;
    }

    @Override
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        if (random != null) {
            shuffleStep();
        }
        return array[indices[i++]];
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * Creates an integer sequence array.
     * @param length The length of the resulting array.
     * @return an array holding values [0, 1, ..., length - 1]
     */
    private int[] sequence(int length) {
        final int[] ret = new int[length];
        for (int i = 0; i < length; i++) {
            ret[i] = i;
        }
        return ret;
    }

    /**
     * Perfoms a Fisher–Yates shuffle step from the current position.
     */
    private void shuffleStep() {
        // Swap the index at position i with a random subsequent index.
        final int j = random.nextInt(indices.length - i) + i;
        final int tmp = indices[j];
        indices[j] = indices[i];
        indices[i] = tmp;
    }
}
