/*
  UpdatableSortedLinkedListWithForeignIndex.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
    public synchronized IndexableUpdatableSortedLinkedListItem removeByKey(Object key) throws UpdatableSortedLinkedListKilledException {
        IndexableUpdatableSortedLinkedListItem item = 
            (IndexableUpdatableSortedLinkedListItem) map.get(key);
        if(item != null) remove(item);
        checkList();
        return item;
    }
}
