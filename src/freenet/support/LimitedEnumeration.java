/*
  LimitedEnumeration.java / Freenet
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
import java.util.NoSuchElementException;

/** We kept remaking this everywhere so wtf.
  * @author tavin
  */
public final class LimitedEnumeration implements Enumeration {

    private Object next;
    
    public LimitedEnumeration() {
        next = null;
    }

    public LimitedEnumeration(Object loner) {
        next = loner;
    }
        
    public final boolean hasMoreElements() {
        return next != null;
    }
    
    public final Object nextElement() {
        if (next == null) throw new NoSuchElementException();
        try {
            return next;
        }
        finally {
            next = null;
        }
    }
}
