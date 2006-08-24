package freenet.support;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Keep the last N NumberedItem's by number. Supports fetching
 * by number, fetching all items with number greater than a
 * certain value, and inserting an item with a given number.
 * Any items that don't fit are dropped from the end with the
 * lower numerical value. 
 */
public class NumberedRecentItems {

    private NumberedItem[] items;
    private int count;
    private Comparator myComparator;
    /**
     * Create a NumberedRecentItems list.
     * @param maxSize The maximum number of NumberedItems to keep.
     * @param wrap If true, comparisons are modulo 2^63-1.
     * We calculate the direct and wrapped distance, and use
     * whichever is shorter to determine the direction.
     */
    public NumberedRecentItems(int maxSize, boolean wrap) {
        items = new NumberedItem[maxSize];
        count = 0;
        myComparator = new NumberedItemComparator(wrap);
    }

    public synchronized NumberedItem get(int num) {
        int x = java.util.Arrays.binarySearch(items, new Integer(num), myComparator);
        if(x >= 0) return items[x];
        return null;
    }
    
    /**
     * Add an item.
     * @return True if we added a new item. Must return false if
     * it was already present. Also returns false if the data is
     * so old that we don't want it.
     */
    public synchronized boolean add(NumberedItem item) {
        long num = item.getNumber();
        int x = Arrays.binarySearch(items, new Long(num), myComparator);
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Search pos: "+x);
        if(x >= 0) return false; // already present
        count++;
        if(x == -1) {
            // insertion point = 0
            // [0] > item
            // do nothing
            return false;
//            if(count < items.length) {
//                
//                System.arraycopy(items, 0, items, 1, items.length);
//                items[0] = item;
//            } else {
//                // [0] is greater than item, drop it
//                return false;
//            }
        }
        if(x == -(items.length)-1) {
            // All items less than this item
            // Shift back one, then set last item
            System.arraycopy(items, 1, items, 0, items.length-1);
            items[items.length-1] = item;
        } else if(x == -2) {
            // [1] is greater than item, [0] is less
            items[0] = item;
        } else {
            // In the middle somewhere
            int firstGreaterItem = (-x)-1;
            // [firstGreaterItem]...[size-1] remain constant
            // move the rest back one
            System.arraycopy(items, 1, items, 0, firstGreaterItem-1);
            items[firstGreaterItem-1] = item;
        }
        // FIXME: remove when confident
        checkSorted();
        return true;
    }

    private synchronized void checkSorted() {
        long prevNum = -1;
        for(int i=0;i<Math.min(count, items.length);i++) {
            NumberedItem item = items[i];
            long num = item == null ? -1 : item.getNumber();
            if((item != null) && (num < 0))
                throw new IllegalStateException("getNumber() must return positive numbers");
            if((num < prevNum) || ((num != -1) && (num == prevNum))) {
                throw new IllegalStateException("Must be higher than prev: "+num+" "+prevNum);
            }
            prevNum = num;
        }
    }
    
    public synchronized NumberedItem[] getAfter(long target) {
        int x = Arrays.binarySearch(items, new Long(target), myComparator);
        if(x == items.length-1) return null;
        if(x >= 0) {
            NumberedItem[] out = new NumberedItem[items.length-x-1];
            System.arraycopy(items, x+1, out, 0, items.length-x-1);
            return out;
        }
        // Otherwise is not an exact match
        if(x == -items.length-1) return null;
        int firstGreater = (-x)-1;
        NumberedItem[] out = new NumberedItem[items.length-firstGreater];
        System.arraycopy(items, firstGreater, out, 0, items.length-firstGreater);
        return out;
    }
}
