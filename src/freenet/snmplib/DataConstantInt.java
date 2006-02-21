package freenet.snmplib;


public class DataConstantInt implements DataFetcher {
	private String OID;
	private int value;
	
	public DataConstantInt(String oID, int value) {
		this.OID = oID;
		this.value = value;
	}
	
	public String getSNMPOID() {
		return OID;
	}

	public Object getSNMPData() {
		return new Integer(value);
	}
}
