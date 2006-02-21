package freenet.snmplib;

public class SNMPInteger32 extends SNMPTypeWrapperNum {
	public SNMPInteger32() { super(); }
	public SNMPInteger32(long value) { super(value); }
	
	public void setValue(long value) {
		// TODO: make it prettier!
		this.value = new Long(value).intValue();
		//System.err.println("Value cut from: " + value + " to " + this.value);
	}
	
	protected void init() {
		this.typeID = 0x02;
	}
}
