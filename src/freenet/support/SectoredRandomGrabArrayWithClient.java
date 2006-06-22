package freenet.support;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithClient extends SectoredRandomGrabArray implements RemoveRandomWithClient {

	private final Object client;
	
	public SectoredRandomGrabArrayWithClient(Object client, RandomSource rand) {
		super(rand);
		this.client = client;
	}

	public Object getClient() {
		return client;
	}
	
}
