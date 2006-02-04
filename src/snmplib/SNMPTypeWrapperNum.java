package snmplib;

public abstract class SNMPTypeWrapperNum {
	protected long value;
	protected byte typeID;
	
	/**
	 * This methd is used to initialize for instance typeID;
	 */
	protected abstract void init();
	
	public SNMPTypeWrapperNum() {
		this(0);
	}
	
	public SNMPTypeWrapperNum(long value) {
		this.setValue(value);
		init();
	}
	
	//public final byte typeID;
	//= 0x02;
	public byte getTypeID() {
		System.err.println("Returning " + Integer.toHexString(typeID) + " for a " + this.getClass().toString());
		return typeID;
	}
	
	public long getValue() {
		return value;
	}
	
	public void setValue(long value) {
		this.value = value;
	}
	
	public Object clone() {
		Object ret = null;
		try {
			ret = this.getClass().newInstance();
			((SNMPTypeWrapperNum)ret).setValue(getValue());
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ret;
	}
	
	public String toString() {
		return Long.toString(value);
	}

}
