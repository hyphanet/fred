package freenet.client.filter;

public class TheoraBitstreamFilter extends OggBitstreamFilter {
	static final byte[] magicNumber = new byte[] {0x74, 0x68, 0x65, 0x6f, 0x72, 0x61};

	protected TheoraBitstreamFilter(OggPage page) {
		super(page);
	}

}
