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
		Logger.minor(this, "request rejected overload: "+this);
	}

	public synchronized void requestCompleted() {
        _totalPackets++;
        _simulatedWindowSize += (PACKET_TRANSMIT_INCREMENT / _simulatedWindowSize);
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
