package freenet.snmplib;

import java.lang.reflect.InvocationTargetException;
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
//			if (o instanceof Integer) {
//				int dlen = intToBytes(((Integer)o).intValue(), buf, offset);
			if (o == null) {
				buf[offset++] = 0x00;
				buf[offset++] = 0x05;
			} else if (o instanceof Long) {
				int dlen = intToBytes(((Long)o).longValue(), buf, offset);
				offset += dlen;
				offset += intToBERBytes(dlen, buf, offset);
				buf[offset++] = 0x02;
			} else if (o instanceof SNMPTypeWrapperNum) {
				int dlen = intToBytes(((SNMPTypeWrapperNum)o).getValue(), buf, offset);
				offset += dlen;
				offset += intToBERBytes(dlen, buf, offset);
				buf[offset++] = ((SNMPTypeWrapperNum)o).getTypeID();
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
	
	
	private int intToBytes(long i, byte[] buf, int offset) {
		// TODO: handle negative numbers also!!!!
		int inoffset = offset;
		if (i == 0) {
			buf[offset++] = 0;
		} else {
			for (; i > 0 ; i = i / 256) {
				buf[offset] = (byte)(i % 256);
				offset++;
			}
		}
		// make the number unsigned
		/*
		 * No, we should allow signed numbers
		 * if (buf[offset-1]<0)
			buf[offset++] = 0;
			*/
		return (offset - inoffset);
	}
	
	private int intToBERBytes(long i, byte[] buf, int offset) {
		//String bs = Long.toBinaryString(i);
		//int len = (bs.length()%7);
		//bs = ("0000000" + bs).substring(len);
		//char bits[] = bs.toCharArray();
		//int eatenbits = 0;
		buf[offset] = 0;
		int inoffset = offset; 
		//for (int j = bits.length - 1 ; j >= 0 ; j--) {
		long j = i;
		//System.out.print("intToBERBytes: " + i + ": ");
		if (i == 0) {
			offset++;
		} else {
			for ( ; j > 0 ; j = j/128) {
				buf[offset++] += (byte)(j%128);
				buf[offset]    = (byte)((j > 127)?128:0);
				//System.out.print("[" + (j%128) + " + " + ((j > 127)?128:0) + " = " + ((j%128) + ((j > 127)?128:0) ) + "] ");
			}
			
			// now; turn it around!
			if ((offset - inoffset) > 1) {
			//	System.err.println("Started at: " + inoffset + ", ended at: " + offset);
				for (int p = 0 ; p < (offset - inoffset)/2  ;p++) {
			//		System.err.println("stap: " + (offset-p-1) + " <-> " + (inoffset+p));
					byte tmp = buf[offset-p-1];
					buf[offset-p-1] = buf[inoffset+p];
					buf[inoffset+p] = tmp;
				}
			}
			//System.err.println();
			/*
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
			 */
		}
		return (offset - inoffset);
	}
	
	
	public void putSNMPTypeWrapperNum(SNMPTypeWrapperNum o) {
		addToTop(o.clone());
	}
	
	/*public void putInteger(int i) {
		addToTop(new Integer(i));
	}*/
	public void putTimeticks(long i) {
		addToTop(new SNMPTimeTicks(i));
	}
	
	public void putInteger(long i) {
		addToTop(new Long(i));
	}
	
	public void putCounter32(long i) {
		addToTop(new SNMPCounter32(i));
	}
	
	public void putNull() {
		addToTop(null);
	}
	
	public void putOctetString(byte buf[]) {
		addToTop(new ByteArrWrapper((byte[])buf.clone(), (byte)0x04));
	}

	public void putOID(long buf[]) {
		byte bufa[] = new byte[10*buf.length];
		int offset = 1;
		bufa[0] = 0x2b;
		for (int i = 2 ; i < buf.length ; i++) {
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
