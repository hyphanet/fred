/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;

public class ThrottleWindowManager {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	static final float PACKET_DROP_DECREASE_MULTIPLE = 0.97f;
	static final float PACKET_TRANSMIT_INCREMENT = (4 * (1 - (PACKET_DROP_DECREASE_MULTIPLE * PACKET_DROP_DECREASE_MULTIPLE))) / 3;

	private long _totalPackets = 0, _droppedPackets = 0;
	private double _simulatedWindowSize = 2;
	
	private final Node node;
	
	public ThrottleWindowManager(double def, SimpleFieldSet fs, Node node) {
		this.node = node;
		if(fs != null) {
			_totalPackets = fs.getInt("TotalPackets", 0);
			_droppedPackets = fs.getInt("DroppedPackets", 0);
			_simulatedWindowSize = fs.getDouble("SimulatedWindowSize", def);
		} else {
			_simulatedWindowSize = def;
		}
	}

	public synchronized double currentValue(boolean realTime) {
		if (_simulatedWindowSize < 1.0) {
			_simulatedWindowSize = 1.0F;
		}
		return _simulatedWindowSize * Math.max(1, node.peers.countNonBackedOffPeers(realTime));
	}

	public synchronized void rejectedOverload() {
		_droppedPackets++;
		_totalPackets++;
		_simulatedWindowSize *= PACKET_DROP_DECREASE_MULTIPLE;
        if(logMINOR)
        	Logger.minor(this, "request rejected overload: "+this);
	}

	public synchronized void requestCompleted() {
        _totalPackets++;
        _simulatedWindowSize += (PACKET_TRANSMIT_INCREMENT / _simulatedWindowSize);
        if(logMINOR)
        	Logger.minor(this, "requestCompleted on "+this);
	}

	@Override
	public synchronized String toString() {
		return  super.toString()+" w: "
				+ _simulatedWindowSize + ", d:"
				+ (((float) _droppedPackets / (float) _totalPackets)) + '=' +_droppedPackets+ '/' +_totalPackets;
	}

	public SimpleFieldSet exportFieldSet(boolean shortLived) {
		SimpleFieldSet fs = new SimpleFieldSet(shortLived);
		fs.putSingle("Type", "ThrottleWindowManager");
		fs.put("TotalPackets", _totalPackets);
		fs.put("DroppedPackets", _droppedPackets);
		fs.put("SimulatedWindowSize", _simulatedWindowSize);
		return fs;
	}

	public double realCurrentValue() {
		return _simulatedWindowSize;
	}
}
