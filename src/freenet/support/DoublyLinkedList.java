/*
  DoublyLinkedList.java / Freenet
  Copyright (C) tavin
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

import java.util.Enumeration;

/**
 * Framework for managing a doubly linked list.
 * @author tavin
 */
public interface DoublyLinkedList {

    public abstract Object clone();
    
    public interface Item {
        Item getNext();
        Item setNext(Item i);
        Item getPrev();
        Item setPrev(Item i);
        // Strictly for sanity checking
        DoublyLinkedList getParent();
        DoublyLinkedList setParent(DoublyLinkedList l);
    }
    
    void clear();
    int size();
    boolean isEmpty();
    Enumeration elements();   // for consistency w/ typical Java API

    /**
     * Returns the first item.
     */
    Item head();
    /**
     * Returns the last item.
     */
    Item tail();

    /**
     * Puts the item before the first item.
     */
    void unshift(Item i);
    void unshift(DoublyLinkedList l);
    /**
     * Removes and returns the first item.
     */
    Item shift();
    DoublyLinkedList shift(int n);

    /**
     * Puts the item after the last item.
     */
    void push(Item i);
    void push(DoublyLinkedList l);
    /**
     * Removes and returns the last item.
     */
    Item pop();
    DoublyLinkedList pop(int n);

    boolean hasNext(Item i);
    boolean hasPrev(Item i);

    Item next(Item i);
    Item prev(Item i);

    Item remove(Item i);

    void insertPrev(Item i, Item j);
    void insertPrev(Item i, DoublyLinkedList l);
    void insertNext(Item i, Item j);
    void insertNext(Item i, DoublyLinkedList l);
}



