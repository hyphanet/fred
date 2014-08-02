package freenet.clients.fcp;

public class ProbeOverallBulkOutputCapacityUsage extends FCPResponse {

	public ProbeOverallBulkOutputCapacityUsage(String identifier,
			byte bandwidthClassForCapacityUsage, float capacityUsage) {
		super(identifier);
		fs.put(OUTPUT_BANDWIDTH_CLASS, bandwidthClassForCapacityUsage);
		fs.put(OVERALL_BULK_OUTPUT_CAPACITY_USAGE, capacityUsage);
	}

	@Override
	public String getName() {
		return "ProbeOverallBulkOutputCapacityUsage";
	}

}
