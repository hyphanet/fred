/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

public class ProbeRejectStats extends FCPResponse {
    public ProbeRejectStats(String identifier, byte[] stats) {
        super(identifier);
        fs.put(BULK_CHK_REQUEST_REJECTS, (int) stats[0]);
        fs.put(BULK_SSK_REQUEST_REJECTS, (int) stats[1]);
        fs.put(BULK_CHK_INSERT_REJECTS, (int) stats[2]);
        fs.put(BULK_SSK_INSERT_REJECTS, (int) stats[3]);
    }

    @Override
    public String getName() {
        return "ProbeRejectStats";
    }
}
