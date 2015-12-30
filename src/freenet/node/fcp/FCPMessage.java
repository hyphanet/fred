package freenet.node.fcp;

/** Only included so we can construct those messages which are stored inside ClientGet etc. */
public abstract class FCPMessage {
	/*
	 * Fields used by FCP messages. These are in TitleCaps by convention.
	 */
	public static final String BUILD = "Build";
	public static final String CODE = "Code";
	public static final String HTL = "HopsToLive";
	public static final String IDENTIFIER = "Identifier";
	public static final String LINK_LENGTHS = "LinkLengths";
	public static final String LOCAL = "Local";
	public static final String LOCATION = "Location";
	public static final String OUTPUT_BANDWIDTH = "OutputBandwidth";
	public static final String PROBE_IDENTIFIER = "ProbeIdentifier";
	public static final String STORE_SIZE = "StoreSize";
	public static final String TYPE = "Type";
	public static final String UPTIME_PERCENT = "UptimePercent";
	public static final String BULK_CHK_REQUEST_REJECTS = "Rejects.Bulk.Request.CHK";
	public static final String BULK_SSK_REQUEST_REJECTS = "Rejects.Bulk.Request.SSK";
	public static final String BULK_CHK_INSERT_REJECTS = "Rejects.Bulk.Insert.CHK";
	public static final String BULK_SSK_INSERT_REJECTS = "Rejects.Bulk.Insert.SSK";
	public static final String OUTPUT_BANDWIDTH_CLASS = "OutputBandwidthClass";
	public static final String OVERALL_BULK_OUTPUT_CAPACITY_USAGE = "OverallBulkOutputCapacityUsage";
	
}
