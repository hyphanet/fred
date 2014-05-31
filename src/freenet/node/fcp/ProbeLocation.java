/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

/**
 * FCP message sent from the node to the client which includes the location reported by the endpoint.
 */
public class ProbeLocation extends FCPResponse {
    public ProbeLocation(String fcpIdentifier, double location) {
        super(fcpIdentifier);
        fs.put(LOCATION, location);
    }

    @Override
    public String getName() {
        return "ProbeLocation";
    }
}
