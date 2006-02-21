package freenet.snmplib;

public class SNMPTimeTicks extends SNMPTypeWrapperNum {
	public SNMPTimeTicks() { super(); }
	public SNMPTimeTicks(long value) { super(value); }
	
	protected void init() {
		this.typeID = 0x43;
	}
	
	public String toString() {
		long rest = value;
		long dec = rest%100;
		rest = rest/100;
		long sec = rest%60;
		rest = rest/60;
		long min = rest%60;
		rest = rest/60;
		long hour = rest%24;
		rest = rest/24;
		long day = rest;
		return day + ":" + hour + ":" + min + ":" + sec + "." + dec;
	}
}
