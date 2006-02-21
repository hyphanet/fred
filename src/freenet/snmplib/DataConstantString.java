package freenet.snmplib;

public class DataConstantString implements DataFetcher {
	private String OID;
	private String value;
	
	public DataConstantString(String oID, String value) {
		this.OID = oID;
		this.value = value;
	}
	
	public String getSNMPOID() {
		return OID;
	}

	public Object getSNMPData() {
		return value;
	}
}
