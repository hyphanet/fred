/*
  ThrottleWindowManager.java / Freenet
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
import freenet.support.SimpleFieldSet;

public class ThrottleWindowManager {

	static final float PACKET_DROP_DECREASE_MULTIPLE = 0.97f;
	static final float PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;

	private long _totalPackets = 0, _droppedPackets = 0;
	private double _simulatedWindowSize = 2;
	
	public ThrottleWindowManager(double def, SimpleFieldSet fs) {
		if(fs != null) {
			_totalPackets = fs.getInt("TotalPackets", 0);
			_droppedPackets = fs.getInt("DroppedPackets", 0);
			_simulatedWindowSize = fs.getDouble("SimulatedWindowSize", def);
		} else {
			_simulatedWindowSize = def;
		}
	}

	public synchronized double currentValue() {
		if (_simulatedWindowSize < 1.0) {
			_simulatedWindowSize = 1.0F;
		}
		return _simulatedWindowSize;
	}

	public synchronized void rejectedOverload() {
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "request rejected overload: "+this);
	}

	public synchronized void requestCompleted() {
        _totalPackets++;
        _simulatedWindowSize += (PACKET_TRANSMIT_INCREMENT / _simulatedWindowSize);
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "requestCompleted on "+this);
	}

	public synchronized String toString() {
		return  super.toString()+" w: "
				+ _simulatedWindowSize + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + "="+_droppedPackets+"/"+_totalPackets;
	}

	public SimpleFieldSet exportFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Type", "ThrottleWindowManager");
		fs.put("TotalPackets", _totalPackets);
		fs.put("DroppedPackets", _droppedPackets);
		fs.put("SimulatedWindowSize", _simulatedWindowSize);
		return fs;
	}
}
