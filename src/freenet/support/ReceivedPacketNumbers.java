/*
  ReceivedPacketNumbers.java / Freenet
  Copyright (C) amphibian
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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;


/**
 * @author amphibian
 * 
 * Tracks which packet numbers we have received.
 * Implemented as a sorted list since this is simplest and
 * it's unlikely it will be very long in practice. The 512-
 * packet window provides a practical limit.
 */
public class ReceivedPacketNumbers {

    final LinkedList ranges;
    int lowestSeqNumber;
    int highestSeqNumber;
    final int horizon;
    
    public ReceivedPacketNumbers(int horizon) {
        ranges = new LinkedList();
        lowestSeqNumber = -1;
        highestSeqNumber = -1;
        this.horizon = horizon;
    }
    
    public synchronized void clear() {
        lowestSeqNumber = -1;
        highestSeqNumber = -1;
        ranges.clear();
    }
    
    private static class Range {
        int start; // inclusive
        int end;   // inclusive
        
        public String toString() {
            return "Range:"+start+"->"+end;
        }
    }
    
    /**
     * We received a packet!
     * @param seqNumber The number of the packet.
     * @return True if we stored the packet. False if it is out
     * of range of the current window.
     */
    public synchronized boolean got(int seqNumber) {
        if(seqNumber < 0) throw new IllegalArgumentException();
        if(ranges.isEmpty()) {
            Range r = new Range();
            r.start = r.end = lowestSeqNumber = highestSeqNumber = seqNumber;
            ranges.addFirst(r);
            return true;
        } else {
            ListIterator li = ranges.listIterator();
            Range r = (Range)li.next();
            int firstSeq = r.end;
            if(seqNumber - firstSeq > horizon) {
                // Delete first item
                li.remove();
                r = (Range)li.next();
                lowestSeqNumber = r.start;
            }
            while(true) {
                if(seqNumber == r.start-1) {
                    r.start--;
                    if(li.hasPrevious()) {
                        Range r1 = (Range) li.previous();
                        if(r1.end == seqNumber-1) {
                            r.start = r1.start;
                            li.remove();
                        }
                    } else {
                        lowestSeqNumber = seqNumber;
                    }
                    return true;
                }
                if(seqNumber < r.start-1) {
                    if(highestSeqNumber - seqNumber > horizon) {
                        // Out of window, don't store
                        return false;
                    }
                    Range r1 = new Range();
                    r1.start = r1.end = seqNumber;
                    li.previous(); // move cursor back
                    if(!li.hasPrevious()) // inserting at start??
                        lowestSeqNumber = seqNumber;
                    li.add(r1);
                    return true;
                }
                if((seqNumber >= r.start) && (seqNumber <= r.end)) {
                    // Duh
                    return true;
                }
                if(seqNumber == r.end+1) {
                    r.end++;
                    if(li.hasNext()) {
                        Range r1 = (Range) li.next();
                        if(r1.start == seqNumber+1) {
                            r.end = r1.end;
                            li.remove();
                        }
                    } else {
                        highestSeqNumber = seqNumber;
                    }
                    return true;
                }
                if(seqNumber > r.end+1) {
                    if(!li.hasNext()) {
                        // This is the end of the list
                        Range r1 = new Range();
                        r1.start = r1.end = highestSeqNumber = seqNumber;
                        li.add(r1);
                        return true;
                    }
                }
                r = (Range) li.next();
            }
        }
    }

    /**
     * Have we received packet #seqNumber??
     * @param seqNumber
     * @return
     */
    public synchronized boolean contains(int seqNumber) {
        if(seqNumber > highestSeqNumber)
            return false;
        if(seqNumber == highestSeqNumber)
            return true;
        if(seqNumber == lowestSeqNumber)
            return true;
        if(highestSeqNumber - seqNumber > horizon)
            return true; // Assume we have since out of window
        Iterator i = ranges.iterator();
        Range last = null;
        for(;i.hasNext();) {
            Range r = (Range)i.next();
            if(r.start > r.end) {
                Logger.error(this, "Bad Range: "+r);
            }
            if((last != null) && (r.start < last.end)) {
                Logger.error(this, "This range: "+r+" but last was: "+last);
            }
            if((r.start <= seqNumber) && (r.end >= seqNumber))
                return true;
        }
        return false;
    }

    /**
     * @return The highest packet number seen so far.
     */
    public synchronized int highest() {
        return highestSeqNumber;
    }
    
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(super.toString());
        sb.append(": max=");
        synchronized(this) {
			sb.append(highestSeqNumber);
			sb.append(", min=");
			sb.append(lowestSeqNumber);
			sb.append(", ranges=");
            Iterator i = ranges.iterator();
            while(i.hasNext()) {
                Range r = (Range) i.next();
                sb.append(r.start);
                sb.append('-');
                sb.append(r.end);
                if(i.hasNext()) sb.append(',');
            }
        }
        return sb.toString();
    }
}
