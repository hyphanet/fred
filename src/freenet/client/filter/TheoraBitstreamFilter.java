package freenet.client.filter;

import freenet.support.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TheoraBitstreamFilter extends OggBitstreamFilter {
    private final TheoraPacketFilter parser;

    protected TheoraBitstreamFilter(OggPage page) {
        super(page);
        parser = new TheoraPacketFilter();
    }

    @Override
    OggPage parse(OggPage page) throws IOException {
        page = super.parse(page);
        if (!isValidStream) {
            return null;
        }

        List<CodecPacket> parsedPackets = new ArrayList<>();
        for (CodecPacket packet : page.asPackets()) {
            try {
                parsedPackets.add(parser.parse(packet));
            } catch (DataFilterException e) { // skip packet
                Logger.minor(this, e.getLocalizedMessage());
            }
        }

        page = new OggPage(page, parsedPackets);
        return page;
    }
}
