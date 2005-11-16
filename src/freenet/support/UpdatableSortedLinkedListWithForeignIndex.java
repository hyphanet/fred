package freenet.support;

import java.util.HashMap;

/**
 * UpdatableSortedLinkedList plus a hashtable. Each item has
 * an indexItem(), which we use to track them. This is completely
 * independant of their sort order, hence "foreign".
 * Note that this class, unlike its parent, does not permit 
 * duplicates.
 */
public class UpdatableSortedLinkedListWithForeignIndex extends UpdatableSortedLinkedList {

    final HashMap map;

    public UpdatableSortedLinkedListWithForeignIndex() {
        super();
        map = new HashMap();
    }
    
    public synchronized void add(UpdatableSortedLinkedListItem item) throws UpdatableSortedLinkedListKilledException {
        if(!(item instanceof IndexableUpdatableSortedLinkedListItem)) {
            throw new IllegalArgumentException();
        }
        IndexableUpdatableSortedLinkedListItem i = (IndexableUpdatableSortedLinkedListItem)item;
        if(map.get(i.indexValue()) != null) {
            // Ignore duplicate
            Logger.error(this, "Ignoring duplicate: "+i+" was already present: "+map.get(i.indexValue()));
            return;
        }
        super.add(i);
        map.put(i.indexValue(), item);
        checkList();
    }
    
    public synchronized UpdatableSortedLinkedListItem remove(UpdatableSortedLinkedListItem item) throws UpdatableSortedLinkedListKilledException {
        map.remove(((IndexableUpdatableSortedLinkedListItem)item).indexValue());
        return super.remove(item);
    }

    public synchronized boolean containsKey(Object o) {
        return map.containsKey(o);
    }

    /**
     * Remove an element from the list by its key.
     * @throws UpdatableSortedLinkedListKilledException 
     */
    public synchronized void removeByKey(Object key) throws UpdatableSortedLinkedListKilledException {
        IndexableUpdatableSortedLinkedListItem item = 
            (IndexableUpdatableSortedLinkedListItem) map.get(key);
        if(item != null) remove(item);
        checkList();
    }
}
