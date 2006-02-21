package freenet.snmplib;

import java.util.Stack;

public class BERDecoder {
	private byte[] buf;
	private int ptr = 0;
	private Stack seqStack;
	
	public BERDecoder(byte[] buf) {
		this.buf = buf;
		seqStack = new Stack();
	}
	
	public void startSequence() throws BadFormatException {
		startSequence((byte)0x30);
	}
	
	public void startSequence(byte id) throws BadFormatException {
		if (buf[ptr] != id)
			throw new BadFormatException("Unknown Sequence (expected: 0x" +
					Integer.toHexString(id) + ", got: 0x" +
					Integer.toHexString(buf[ptr]) + ")");
		ptr++;
		int len = readBERInt();
		seqStack.push(new Integer(ptr + len));
		seqStack.push(new Integer(len));
	}
	
	public void endSequence() throws BadFormatException {
		int length = ((Integer)seqStack.pop()).intValue();
		int pos = ((Integer)seqStack.pop()).intValue();
		if (pos != ptr)
			throw new BadFormatException("Wrong length of field " + 
					length + ":" + pos + ":" + ptr);
	}
	
	public boolean sequenceHasMore() {
		//int length = ((Integer)seqStack.peek()).intValue();
		int pos = ((Integer)seqStack.get(seqStack.size()-2)).intValue();
		return (pos != ptr);
	}
	
	public byte peekRaw() {
		return buf[ptr];
	}
	
	public long[] fetchOID() throws BadFormatException {
		startSequence((byte)0x06);
		long[] ret = readOID();
		endSequence();
		return ret;
	}
	
	private long[] readOID() throws BadFormatException {
		if (buf[ptr] != 0x2b)
			throw new BadFormatException("Bad start of OID");
		int inptr = ptr;
		ptr++;
		int length = ((Integer)seqStack.peek()).intValue();
		if (length < 2)
			return new long[0];
		long ret[] = new long[length]; // it won't getlonger then this
		int i;
		for(i = 0; i < length ; i++) {
			ret[i] = readBERInt();
			if ((ptr - inptr) >= length)
				break;
		}
		
		if (i < length) { // Bring out the scissors
			long ret2[] = (long[])ret.clone();
			ret = new long[i + 1];
			for ( ; i >= 0 ; i--)
				ret[i] = ret2[i];
		}
		return ret;
	}
	
	
	public byte[] fetchOctetString() throws BadFormatException {
		startSequence((byte)0x04);
		byte[] ret = readOctetString();
		endSequence();
		return ret;
	}
	
	private byte[] readOctetString() {
		int length = ((Integer)seqStack.peek()).intValue();
		byte ret[] = new byte[length];
		for(int i = 0; i < length ; i++) {
			ret[i] = buf[ptr++];
		}
		return ret;
	}

	
	public void fetchNull() throws BadFormatException {
		startSequence((byte)0x05);
		endSequence();
	}

	public int fetchInt() throws BadFormatException {
		startSequence((byte)0x02);
		int ret = readInt();
		endSequence();
		return ret;
	}
	
	private int readInt() {
		int length = ((Integer)seqStack.peek()).intValue();
		int ret = 0;
		for ( ; length > 0 ; length--) {
			ret = ret * 256;
			ret = ret + ((((int)buf[ptr])+256)%256);
			ptr++;
		}
		return ret;
	}
	
	
	
	private int readBERInt() {
		int ret = 0;
		do {
			ret = ret * 128;
			ret = ret + ((((int)buf[ptr])+128)%128);
			ptr++;
		} while (buf[ptr-1] < 0);
		return ret;
	}

}
