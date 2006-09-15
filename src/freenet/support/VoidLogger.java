/*
  VoidLogger.java / Freenet, Java Adaptive Network Client
  Created on Mar 18, 2004
  Copyright (C) 2004 Iakin
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

/**
 * @author Iakin
 * A LoggerHook implementation that just passes any supplied log messages on to /dev/null 
 */
public class VoidLogger extends Logger
{

	public void log(Object o, Class source, String message, Throwable e, int priority) {
	}

	public void log(Object source, String message, int priority) {
	}

	public void log(Object o, String message, Throwable e, int priority) {
	}

	public void log(Class c, String message, int priority) {
	}

	public void log(Class c, String message, Throwable e, int priority) {
	}

	public long minFlags() {
		return 0;
	}

	public long notFlags() {
		return 0;
	}

	public long anyFlags() {
		return 0;
	}

	public boolean instanceShouldLog(int priority, Class c) {
		return false;
	}

	public boolean instanceShouldLog(int prio, Object o) {
		return false;
	}

	public void setThreshold(int thresh) {
	}

	public int getThreshold() {
		return 0;
	}

	public void setThreshold(String symbolicThreshold) {
	}

	public void setDetailedThresholds(String details) {
	}
	
}