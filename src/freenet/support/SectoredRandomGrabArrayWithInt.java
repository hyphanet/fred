package freenet.support;

import freenet.client.async.SendableRequest;
import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(RandomSource rand, int number) {
		super(rand);
		this.number = number;
	}

	public int getNumber() {
		return number;
	}

}
