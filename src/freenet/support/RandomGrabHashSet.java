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
    private final Random random; 
    
    /**
     * Stores all elements.
     * Allows {@link #getRandom()} to execute in O(1).
     */
    private final ArrayList<E> data = new ArrayList<E>();
    
    /**
     * Tells which slot each element resides in {{@link #data}}.
     * This allows {@link #contains(Object)} and {@link #remove(Object)} to execute in amortised O(1).
     */
    private final HashMap<E, Integer> index = new HashMap<E, Integer>();
    
    public RandomGrabHashSet(final Random myRandom) {
        random = myRandom;
    }
    
    
    /***
     * Debug function.
     * Add assert(indexIsValid()) to any functions which modify stuff.
     */
    protected boolean indexIsValid() {
        if(index.size() != data.size())
            return false;
        
        for(Entry<E, Integer> entry : index.entrySet()) {
            if(!data.get(entry.getValue()).equals(entry.getKey()))
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
        
        data.add(item);
        index.put(item, data.size()-1);
        
        assert(index.size() == data.size());
        assert(data.get(index.get(item)) == item);
    }
    
    public boolean contains(final E item) {
        return index.containsKey(item);
    }
    
    public void remove(final E toRemove) {
        final Integer indexOfRemovedItem = index.remove(toRemove) ;
        if(indexOfRemovedItem == null)
            throw new NoSuchElementException();
        
        assert(data.get(indexOfRemovedItem).equals(toRemove));
        
        // We cannot use ArrayList.remove() because it would shift all following elements.
        // Instead of that, we replace the now-empty slot with the last element
        final int indexOfLastItem = data.size()-1;
        final E lastItem = data.remove(indexOfLastItem);
        if(indexOfRemovedItem != indexOfLastItem) {
            data.set(indexOfRemovedItem, lastItem);
            index.put(lastItem, indexOfRemovedItem);
        }
        
        assert(index.size() == data.size());
        assert(index.get(toRemove) == null);
        assert(lastItem.equals(toRemove) || data.get(index.get(lastItem)).equals(lastItem));
    }
    
    public int size() {
        assert(data.size() == index.size());
        return data.size();
    }
    
    public E getRandom() {
        return data.get(random.nextInt(data.size()));
    }

}
