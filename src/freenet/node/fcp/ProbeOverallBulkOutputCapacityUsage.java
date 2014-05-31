/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

public class ProbeOverallBulkOutputCapacityUsage extends FCPResponse {
    public ProbeOverallBulkOutputCapacityUsage(String identifier, byte bandwidthClassForCapacityUsage,
            float capacityUsage) {
        super(identifier);
        fs.put(OUTPUT_BANDWIDTH_CLASS, bandwidthClassForCapacityUsage);
        fs.put(OVERALL_BULK_OUTPUT_CAPACITY_USAGE, capacityUsage);
    }

    @Override
    public String getName() {
        return "ProbeOverallBulkOutputCapacityUsage";
    }
}
