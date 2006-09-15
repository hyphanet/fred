/*
  RunningAverage.java / Freenet
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

package freenet.support.math;

import java.io.Serializable;

public interface RunningAverage extends Serializable {
    
    public Object clone();
    
	public double currentValue();
	public void report(double d);
	public void report(long d);
    /**
     * Get what currentValue() would be if we reported some given value
     * @param r the value to mimic reporting
     * @return the output of currentValue() if we were to report r
     */
    public double valueIfReported(double r);
    /**
     * @return the total number of reports on this RunningAverage so far.
     * Used for weighted averages, confidence/newbieness estimation etc.
     */
    public long countReports();
}
