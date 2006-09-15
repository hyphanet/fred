/*
  MemoryChecker.java / Freenet
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

import freenet.support.Logger;

public class MemoryChecker implements Runnable {
	
	public void run() {
		Runtime r = Runtime.getRuntime();
		while(true) {
			int sleeptime = Node.aggressiveGCModificator;
			if(sleeptime <= 0)
				sleeptime = 250;
			
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			
			for(int i=0;i<120;i++) {
				try {
					Thread.sleep(sleeptime);
				} catch (InterruptedException e) {
					// Ignore
				}
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				if(logMINOR)
					Logger.minor(this, "Memory in use: "+(r.totalMemory()-r.freeMemory()));
			}
			try {
				Thread.sleep(sleeptime);
			} catch (InterruptedException e) {
				// Ignore
			}
			// FIXME
			// Do not remove until all known memory issues fixed,
			// Especially #66
			// This probably reduces performance, but it makes
			// memory usage *more predictable*. This will make
			// tracking down the sort of nasty unpredictable OOMs
			// we are getting much easier. 
			if(Node.aggressiveGCModificator > 0) {
				long beforeGCUsedMemory = (r.totalMemory()-r.freeMemory());
				if(logMINOR) Logger.minor(this, "Memory in use before GC: "+beforeGCUsedMemory);
				long beforeGCTime = System.currentTimeMillis();
				System.gc();
				System.runFinalization();
				long afterGCTime = System.currentTimeMillis();
				long afterGCUsedMemory = (r.totalMemory()-r.freeMemory());
				if(logMINOR) {
					Logger.minor(this, "Memory in use after GC: "+afterGCUsedMemory);
					Logger.minor(this, "GC completed after "+(afterGCTime - beforeGCTime)+"ms and \"recovered\" "+(beforeGCUsedMemory - afterGCUsedMemory)+" bytes, leaving "+afterGCUsedMemory+" bytes used");
				}
			}
		}
	}
}