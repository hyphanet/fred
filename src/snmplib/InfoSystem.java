package snmplib;

import freenet.node.Version;

public class InfoSystem implements MultiplexedDataFetcher {
	long created;

	public InfoSystem() {
		created = System.currentTimeMillis()/10;
	}
	
	public String getSNMPOID(int index) {
		switch (index) {
			
		case 0: //UCD-SNMP-MIB::memTotalReal.0
			return ".1.3.6.1.4.1.2021.4.5.0";
		case 1: //UCD-SNMP-MIB::memAvailReal.0
			return ".1.3.6.1.4.1.2021.4.6.0";
		case 2: //SNMPv2-MIB::sysName.0
			return ".1.3.6.1.2.1.1.5.0";
		case 3: //SNMPv2-MIB::sysUpTime.0
			return ".1.3.6.1.2.1.1.3.0";
			

			
		}
		// default
		return null;
	}

	public Object getSNMPData(String oid) {
		Runtime r = Runtime.getRuntime();
		int oidhc = oid.hashCode();
		//System.err.println("requesting: " + oid);
		if (oid.equals(".1.3.6.1.2.1.1.3.0")) //SNMPv2-MIB::sysUpTime.0
			return new SNMPTimeTicks(System.currentTimeMillis()/10 - created);
		if (oid.equals(".1.3.6.1.4.1.2021.4.5.0")) //UCD-SNMP-MIB::memTotalReal.0
			return new Long(r.totalMemory());
		if (oid.equals(".1.3.6.1.4.1.2021.4.6.0")) //UCD-SNMP-MIB::memAvailReal.0
			return new Long(r.freeMemory());

		
		if (oid.equals(".1.3.6.1.2.1.1.5.0")) //SNMPv2-MIB::sysName.0
			return (Version.nodeName + " " + Version.nodeVersion + " (" + Version.buildNumber() + ")");

		
		if (oid.equals(".1.3.6.1.4.1.2021.4.5")) //UCD-SNMP-MIB::memTotalReal.0
			return new Long(r.totalMemory());
		if (oid.equals(".1.3.6.1.4.1.2021.4.6")) //UCD-SNMP-MIB::memAvailReal.0
			return new Long(r.freeMemory());
		
		return null;
	}
	

}
