package snmplib;

public interface MultiplexedDataFetcher {
	
	/* Return null when the last OID is reached */
	public String getSNMPOID(int index);
	
	/* Must return an Integer or a String */
	public Object getSNMPData(String oid);

}
