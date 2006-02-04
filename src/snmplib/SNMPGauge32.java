package snmplib;

public class SNMPGauge32 extends SNMPTypeWrapperNum {
	public SNMPGauge32() { super(); }
	public SNMPGauge32(long value) { super(value); }
	
	public void setValue(long value) {
		// TODO: make it prettier!
		this.value = new Long(value).intValue();
		//System.err.println("Value cut from: " + value + " to " + this.value);
	}
	
	protected void init() {
		this.typeID = 0x42;
	}
}
