package freenet.support;

import java.util.Enumeration;

/**
 * @author amphibian
 * 
 * Sorted LinkedList. Keeps track of the maximum and minimum,
 * and provides an update() function to move an item when its
 * value has changed. Allows duplicates.
 */
public class UpdatableSortedLinkedList {

    public UpdatableSortedLinkedList() {
        list = new DoublyLinkedListImpl();
    }
    
    private final DoublyLinkedList list;
    
    public synchronized void add(UpdatableSortedLinkedListItem i) {
        Logger.minor(this, "Add("+i+") on "+this);
        if(list.isEmpty()) {
            list.push(i);
            return;
        }
        if(i.compareTo(list.tail()) >= 0) {
            list.push(i);
            return;
        }
        if(i.compareTo(list.head()) <= 0) {
            list.unshift(i);
            return;
        }
        // Search the list for a good place to put it
        Enumeration e = list.elements();
        UpdatableSortedLinkedListItem prev = null;
        while(e.hasMoreElements()) {
            UpdatableSortedLinkedListItem cur = 
                (UpdatableSortedLinkedListItem) e.nextElement();
            if(prev != null && cur.compareTo(i) >= 0 && prev.compareTo(i) <= 0) {
                list.insertNext(prev, i);
                return;
            }
            Logger.minor(this, "Not matching "+cur+" "+prev);
            prev = cur;
        }
        throw new IllegalStateException("impossible");
    }

    public synchronized void remove(UpdatableSortedLinkedListItem i) {
        Logger.minor(this, "Remove("+i+") on "+this);
        list.remove(i);
    }
    
    public synchronized void update(UpdatableSortedLinkedListItem i) {
        Logger.minor(this, "Update("+i+") on "+this);
        if(i.compareTo(list.tail()) > 0) {
            list.remove(i);
            list.push(i);
            return;
        }
        if(i.compareTo(list.head()) < 0) {
            list.remove(i);
            list.unshift(i);
            return;
        }
        if(list.head() == list.tail() && i != list.head()) {
            Logger.error(this, "Only 1 element: "+list.head()+" and updating "+i+" on "+this, new Exception("error"));
            add(i);
            return;
        }
        // Forwards or backwards?
        UpdatableSortedLinkedListItem next = (UpdatableSortedLinkedListItem) list.next(i);
        UpdatableSortedLinkedListItem prev = (UpdatableSortedLinkedListItem) list.prev(i);
        if(next == null && prev == null) {
            return;
        }
        if(next != null && prev != null && next.compareTo(i) >= 0 && prev.compareTo(i) <= 0) 
            return; // already exactly where it should be
        if(next == null && prev != null) {
            if(prev.compareTo(i) <= 0) return; // already in right place, at end
        }
        if(next != null && prev == null) {
            if(next.compareTo(i) >= 0) return; // already where it should be
        }
        if(next != null && i.compareTo(next) > 0) {
            // i > next - move forwards
            while(true) {
                prev = next;
                next = (UpdatableSortedLinkedListItem) list.next(next);
                if(next == null) {
                    throw new NullPointerException("impossible - we checked");
                }
                if(i.compareTo(next) < 0 && (prev == null || i.compareTo(prev) > 0)) {
                    list.remove(i);
                    list.insertNext(prev, i);
                    return;
                }
            }
        } else if(prev != null && i.compareTo(prev) < 0) {
            // i < next - move backwards
            while(true) {
                next = prev;
                prev = (UpdatableSortedLinkedListItem) list.prev(prev);
                if(next == null) {
                    throw new NullPointerException("impossible - we checked");
                }
                if(i.compareTo(next) < 0 && i.compareTo(prev) > 0) {
                    list.remove(i);
                    list.insertNext(prev, i);
                    return;
                }
            }
        }
        Logger.error(this, "Could not update "+i, new Exception("error"));
        dump();
        remove(i);
        add(i);
    }

    /**
     * Dump the current status of the list to the log.
     */
    private synchronized void dump() {
        for(Enumeration e=list.elements();e.hasMoreElements();) {
            UpdatableSortedLinkedListItem item = (UpdatableSortedLinkedListItem) e.nextElement();
            Logger.minor(this, item.toString());
        }
    }

    /**
     * @return The number of items in the list.
     */
    public int size() {
        return list.size();
    }

    /**
     * @return an array, in order, of the elements in the list
     */
    public synchronized UpdatableSortedLinkedListItem[] toArray() {
        int size = list.size();
        UpdatableSortedLinkedListItem[] output = 
            new UpdatableSortedLinkedListItem[size];
        int i=0;
        for(Enumeration e = list.elements();e.hasMoreElements();) {
            output[i++] = (UpdatableSortedLinkedListItem)e.nextElement();
            Logger.minor(this, "["+(i-1)+"] = "+output[i-1]);
        }
        return output;
    }

    /**
     * @return Is the list empty?
     */
    public synchronized boolean isEmpty() {
        return list.isEmpty();
    }

    /**
     * @return The item on the list with the lowest value.
     */
    public synchronized UpdatableSortedLinkedListItem getLowest() {
        return (UpdatableSortedLinkedListItem) list.head();
    }
}
