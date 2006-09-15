/*
  SimpleIntNumberedItemComparator.java / Freenet
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

import java.util.Comparator;

public class SimpleIntNumberedItemComparator implements Comparator {

	private final boolean nullAtStart;
	
	public SimpleIntNumberedItemComparator(boolean nullAtStart) {
		this.nullAtStart = nullAtStart;
	}
	
    public int compare(Object o1, Object o2) {
        int x = ocompare(o1, o2);
       //Logger.debug(this, "compare("+o1+","+o2+") = "+x);
        return x;
    }
    
    public int ocompare(Object o1, Object o2) {
        // Nulls at the end of the list
        if((o1 == null) && (o2 == null))
            return 0; // null == null
        if((o1 != null) && (o2 == null))
            return nullAtStart? -1 : 1; // anything > null
        if((o2 != null) && (o1 == null))
            return nullAtStart ? 1 : -1;
        long i1, i2;
        if(o1 instanceof IntNumberedItem)
            i1 = ((IntNumberedItem)o1).getNumber();
        else if(o1 instanceof Integer)
            i1 = ((Integer)o1).intValue();
        else throw new ClassCastException(o1.toString());
        if(o2 instanceof IntNumberedItem)
            i2 = ((IntNumberedItem)o2).getNumber();
        else if(o2 instanceof Integer)
            i2 = ((Integer)o2).intValue();
        else throw new ClassCastException(o2.toString());
        if(i1 == i2) return 0;
        if(i1 > i2) return 1;
        else return -1;
    }
    

}
