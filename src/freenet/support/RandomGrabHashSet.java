/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * HashSet with the ability to return a random item.
 * All operations are amortised O(1).
 * 
 * @author xor (xor@freenetproject.org)
 * @param E The type of the elements.
 */
public final class RandomGrabHashSet<E> {

    /**
     * Random generator used as backend for {@link #getRandom()}.
     */
    private final Random mRandom; 
    
    /**
     * Stores all elements.
     * Allows {@link #getRandom()} to execute in O(1).
     */
    private final ArrayList<E> mData = new ArrayList<E>();
    
    /**
     * Tells which slot each element resides in {{@link #mData}}.
     * This allows {@link #contains(Object)} and {@link #remove(Object)} to execute in amortised O(1).
     */
    private final HashMap<E, Integer> mIndex = new HashMap<E, Integer>();
    
    public RandomGrabHashSet(final Random random) {
        mRandom = random;
    }
    
    
    /***
     * Debug function.
     * Add assert(indexIsValid()) to any functions which modify stuff.
     */
    protected boolean indexIsValid() {
        if(mIndex.size() != mData.size())
            return false;
        
        for(Entry<E, Integer> entry : mIndex.entrySet()) {
            if(!mData.get(entry.getValue()).equals(entry.getKey()))
                return false;
        }
        
        return true;
    }
    
    /**
     * @throws IllegalArgumentException If the element is already contained in the set.
     */
    public void add(final E item) {
        if(contains(item))
            throw new IllegalArgumentException("Element exists already: " + item);
        
        mData.add(item);
        mIndex.put(item, mData.size()-1);
        
        assert(mIndex.size() == mData.size());
        assert(mData.get(mIndex.get(item)) == item);
    }
    
    public boolean contains(final E item) {
        return mIndex.containsKey(item);
    }
    
    public void remove(final E toRemove) {
        final Integer indexOfRemovedItem = mIndex.remove(toRemove) ;
        if(indexOfRemovedItem == null)
            throw new NoSuchElementException();
        
        assert(mData.get(indexOfRemovedItem).equals(toRemove));
        
        // We cannot use ArrayList.remove() because it would shift all following elements.
        // Instead of that, we replace the now-empty slot with the last element
        final int indexOfLastItem = mData.size()-1;
        final E lastItem = mData.remove(indexOfLastItem);
        if(indexOfRemovedItem != indexOfLastItem) {
            mData.set(indexOfRemovedItem, lastItem);
            mIndex.put(lastItem, indexOfRemovedItem);
        }
        
        assert(mIndex.size() == mData.size());
        assert(mIndex.get(toRemove) == null);
        assert(lastItem.equals(toRemove) || mData.get(mIndex.get(lastItem)).equals(lastItem));
    }
    
    public int size() {
        assert(mData.size() == mIndex.size());
        return mData.size();
    }
    
    public E getRandom() {
        return mData.get(mRandom.nextInt(mData.size()));
    }

}
