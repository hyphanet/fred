package snmplib;

public class SNMPTimeTicks {
	private long ticks;
	
	public SNMPTimeTicks(long ticks) {
		this.ticks = ticks;
	}
	
	public long timeValue() {
		return ticks;
	}
	
	public String toString() {
		long rest = ticks;
		long dec = ticks%100;
		rest = rest/100;
		long sec = ticks%60;
		rest = rest/60;
		long min = ticks%60;
		rest = rest/60;
		long hour = ticks%24;
		rest = rest/24;
		long day = ticks;
		return day + ":" + hour + ":" + min + ":" + sec + "." + dec;
	}
}
