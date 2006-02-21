package freenet.snmplib;

public interface DataFetcher {
	
	public String getSNMPOID();
	
	/* Must return an Integer or a String */
	public Object getSNMPData();

}
