package freenet.clients.fcp;

public class ProbeRejectStats extends FCPResponse {

	public ProbeRejectStats(String identifier, byte[] stats) {
		super(identifier);
		fs.put(BULK_CHK_REQUEST_REJECTS, (int)stats[0]);
		fs.put(BULK_SSK_REQUEST_REJECTS, (int)stats[1]);
		fs.put(BULK_CHK_INSERT_REJECTS, (int)stats[2]);
		fs.put(BULK_SSK_INSERT_REJECTS, (int)stats[3]);
	}

	@Override
	public String getName() {
		return "ProbeRejectStats";
	}

}
