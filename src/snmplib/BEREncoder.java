package snmplib;

import java.util.Stack;
import java.util.Vector;

public class BEREncoder {
	private IDVector fields;
	private Stack fstack;
	
	public BEREncoder() {
		this((byte)0x30);
	}
	
	public BEREncoder(byte id) {
		fields = new IDVector(id);
		fstack = new Stack();
		fstack.add(fields);
	}
	
	
	public int toBytes(byte[] buf) {
		while (fields.size() > 1)
			endSequence();
		
		int len = vecToBytes(fields, buf, 0);
		byte tmpbt;
		// Remember.. this function writes backwards first!
		for (int i = 0 ; i < len/2 ; i++) {
			tmpbt = buf[i];
			buf[i] = buf[len - 1 - i];
			buf[len - 1 - i] = tmpbt;
		}
			
		return len;
	}
	
	private int vecToBytes(IDVector v, byte[] buf, int offset) {
		int inoffset = offset;
		for (int i = v.size() - 1 ; i >= 0 ; i--) {
			Object o = v.get(i); 
			if (o instanceof Integer) {
				int dlen = intToBytes(((Integer)o).intValue(), buf, offset);
				offset += dlen;
				offset += intToBERBytes(dlen, buf, offset);
				buf[offset++] = 0x02;
			} else if (o instanceof IDVector) {
				int dlen = vecToBytes((IDVector)o, buf, offset);
				offset += dlen;
				offset += intToBERBytes(dlen, buf, offset);
				buf[offset++] = ((IDVector)o).getVid();
			} else if (o instanceof ByteArrWrapper) {
				byte[] barr = ((ByteArrWrapper)o).arr;
				for (int j = 0 ; j < barr.length ; j++)
					buf[offset + j] = barr[barr.length - 1 - j];
				offset += barr.length;
				offset += intToBERBytes(barr.length, buf, offset);
				buf[offset++] = ((ByteArrWrapper)o).id;
			}
//				myoffset += intToBytes(v.get(i), buf, myoffset);

		}
		
		return (offset - inoffset);
	}
	
	
	private int intToBytes(int i, byte[] buf, int offset) {
		int inoffset = offset;
		if (i == 0) {
			buf[offset++] = 0;
		} else {
			for (; i > 0 ; i = i / 256) {
				buf[offset] = (byte)(i % 256);
				offset++;
			}
		}
		return (offset - inoffset);
	}
	
	private int intToBERBytes(long i, byte[] buf, int offset) {
		String bs = Long.toBinaryString(i);
		int len = (bs.length()%7);
		bs = ("0000000" + bs).substring(len);
		char bits[] = bs.toCharArray();
		int eatenbits = 0;
		buf[offset] = 0;
		int inoffset = offset; 
		//for (int j = bits.length - 1 ; j >= 0 ; j--) {
		for (int j = 0 ; j < bits.length ; j++) {
			if (eatenbits == 7) {
				buf[offset] += 128;
				offset++;
				eatenbits = 0;
				buf[offset] = 0;
			}
			
			buf[offset] |= (bits[j]=='1'?1:0) << (6 - eatenbits);
			eatenbits++;
		}
		offset++;
		return (offset - inoffset);
	}
	
	
	
	public void putInteger(int i) {
		addToTop(new Integer(i));
	}
	
	public void putOctetString(byte buf[]) {
		addToTop(new ByteArrWrapper((byte[])buf.clone(), (byte)0x04));
	}

	public void putOID(long buf[]) {
		byte bufa[] = new byte[10*buf.length];
		int offset = 1;
		bufa[0] = 0x2b;
		for (int i = 0 ; i < buf.length ; i++) {
			offset += intToBERBytes(buf[i], bufa, offset);
		}
		byte bufb[] = new byte[offset];
		for (int i = 0 ; i < bufb.length ; i++)
			bufb[i] = bufa[i];
		
		addToTop(new ByteArrWrapper(bufb, (byte)0x06));
	}

	public void startSequence() {
		startSequence((byte)0x30);
	}
	
	public void startSequence(byte id) {
		IDVector v = new IDVector(id);
		addToTop(v);
		fstack.add(v);
	}
	
	public void endSequence() {
		fstack.pop();
	}
	
	private void addToTop(Object o) {
		((IDVector)fstack.peek()).addElement(o);
	}
	
	private class ByteArrWrapper {
		public byte arr[];
		public byte id;
		
		public ByteArrWrapper(byte arr[], byte id) {
			this.arr = arr;
			this.id = id;
		}
	}
	
	// unpublic
	private class IDVector extends Vector {
		private static final long serialVersionUID = 2689317091785298027L;
		byte vid = (byte)0x30;
		
		public IDVector(byte id) {
			super();
			vid = id;
		}
		
		public byte getVid() {
			return vid;
		}

	}
}
