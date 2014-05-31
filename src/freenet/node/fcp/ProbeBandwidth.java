/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes outgoing bandwidth limit returned by the endpoint.
 */
public class ProbeBandwidth extends FCPResponse {

    /**
     * @param fcpIdentifier FCP-level identifier for pairing requests and responses
     * @param outputBandwidth reported endpoint output bandwidth limit in KiB per second.
     */
    public ProbeBandwidth(String fcpIdentifier, float outputBandwidth) {
        super(fcpIdentifier);
        fs.put(OUTPUT_BANDWIDTH, outputBandwidth);
    }

    @Override
    public String getName() {
        return "ProbeBandwidth";
    }
}
