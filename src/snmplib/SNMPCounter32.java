package snmplib;

public class SNMPCounter32 extends SNMPTypeWrapperNum {
	public SNMPCounter32() { super(); }
	public SNMPCounter32(long value) { super(value); }
	
	public void setValue(long value) {
		// TODO: make it prettier!
		this.value = new Long(value).intValue();
		//System.err.println("Value cut from: " + value + " to " + this.value);
	}
	
	protected void init() {
		this.typeID = 0x41;
	}
}
