/*
  Location.java / Freenet
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

package freenet.node;

import freenet.crypt.RandomSource;

/**
 * @author amphibian
 *
 * Location of a node in the keyspace. ~= specialization.
 * Simply a number from 0.0 to 1.0.
 */
public class Location {
    private double loc;
    private int hashCode;
    
    private Location(double location) {
        setValue(location);
    }

    public Location(String init) throws FSParseException {
        try {
            setValue(Double.parseDouble(init));
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        }
    }

    public double getValue() {
        return loc;
    }

    /**
     * @return A random Location to initialize the node to.
     */
    public static Location randomInitialLocation(RandomSource r) {
        return new Location(r.nextDouble());
    }

    public void setValue(double newLoc) {
        if((loc < 0.0) || (loc > 1.0))
            throw new IllegalArgumentException();
        this.loc = newLoc;
        long l = Double.doubleToLongBits(newLoc);
        hashCode = ((int)(l >>> 32)) ^ ((int)l);
    }
    
    public boolean equals(Object o) {
        if(o instanceof Location) {
            return Math.abs(((Location)o).loc - loc) <= Double.MIN_VALUE;
        }
        return false;
    }
    
    public int hashCode() {
        return hashCode;
    }

    /**
     * Randomize the location.
     */
    public synchronized void randomize(RandomSource r) {
        setValue(r.nextDouble());
    }
}
