package freenet.support;

import java.util.HashMap;

/**
 * UpdatableSortedLinkedList plus a hashtable. Each item has
 * an indexItem(), which we use to track them. This is completely
 * independant of their sort order, hence "foreign".
 */
public class UpdatableSortedLinkedListWithForeignIndex extends UpdatableSortedLinkedList {

    final HashMap map;

    public UpdatableSortedLinkedListWithForeignIndex() {
        super();
        map = new HashMap();
    }
    
    public synchronized void add(UpdatableSortedLinkedListItem item) {
        if(!(item instanceof IndexableUpdatableSortedLinkedListItem)) {
            throw new IllegalArgumentException();
        }
        IndexableUpdatableSortedLinkedListItem i = (IndexableUpdatableSortedLinkedListItem)item;
        super.add(i);
        map.put(i.indexValue(), item);
    }
    
    public synchronized void remove(UpdatableSortedLinkedListItem item) {
        super.remove(item);
        map.remove(((IndexableUpdatableSortedLinkedListItem)item).indexValue());
    }

    public synchronized boolean containsKey(Object o) {
        return map.containsKey(o);
    }

    /**
     * Remove an element from the list by its key.
     */
    public synchronized void removeByKey(Object key) {
        IndexableUpdatableSortedLinkedListItem item = 
            (IndexableUpdatableSortedLinkedListItem) map.get(key);
        if(item != null) remove(item);
    }
}
