/*
Dijjer - A Peer to Peer HTTP Cache
Copyright (C) 2004,2005  Change.Tv, Inc

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package freenet.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import freenet.io.WritableToDataOutputStream;
import freenet.io.comm.Peer;
import freenet.keys.Key;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class Serializer {

    public static final String VERSION = "$Id: Serializer.java,v 1.5 2005/09/15 18:16:04 amphibian Exp $";

	public static List readListFromDataInputStream(Class elementType, DataInputStream dis) throws IOException {
		LinkedList ret = new LinkedList();
		int length = dis.readInt();
		for (int x = 0; x < length; x++) {
			ret.add(readFromDataInputStream(elementType, dis));
		}
		return ret;
	}

	public static Object readFromDataInputStream(Class type, DataInputStream dis) throws IOException {
		if (type.equals(Boolean.class)) {
			return Boolean.valueOf(dis.read() == 1);
		} else if (type.equals(Byte.class)) {
			return new Byte((byte) dis.read());
		} else if (type.equals(Short.class)) {
			return new Short(dis.readShort());
		} else if (type.equals(Integer.class)) {
			return new Integer(dis.readInt());
		} else if (type.equals(Long.class)) {
			return new Long(dis.readLong());
		} else if (type.equals(Double.class)) {
		    return new Double(dis.readDouble());
		} else if (type.equals(String.class)) {
			int length = dis.readInt();
			StringBuffer sb = new StringBuffer(length);
			for (int x = 0; x < length; x++) {
				sb.append(dis.readChar());
			}
			return sb.toString();
		} else if (type.equals(Buffer.class)) {
			return new Buffer(dis);
		} else if (type.equals(ShortBuffer.class)) {
		    return new ShortBuffer(dis);
//		} else if (type.equals(VeryLongInteger.class)) {
//			return new VeryLongInteger(dis);
		} else if (type.equals(Peer.class)) {
			return new Peer(dis);
		} else if (type.equals(BitArray.class)) {
			return new BitArray(dis);
		} else if (type.equals(Key.class)) {
		    return Key.read(dis);
		} else {
			throw new RuntimeException("Unrecognised field type: " + type);
		}
	}

	public static void writeToDataOutputStream(Object object, DataOutputStream dos) throws IOException {
		Class type = object.getClass();
		if (type.equals(Boolean.class)) {
			dos.write(((Boolean) object).booleanValue() ? 1 : 0);
		} else if (type.equals(Byte.class)) {
			dos.write(((Byte) object).byteValue());
		} else if (type.equals(Short.class)) {
			dos.writeShort(((Short) object).shortValue());
		} else if (type.equals(Integer.class)) {
			dos.writeInt(((Integer) object).intValue());
		} else if (type.equals(Long.class)) {
			dos.writeLong(((Long) object).longValue());
		} else if (type.equals(Double.class)) {
		    dos.writeDouble(((Double) object).doubleValue());
		} else if (type.equals(String.class)) {
			String s = (String) object;
			dos.writeInt(s.length());
			for (int x = 0; x < s.length(); x++) {
				dos.writeChar(s.charAt(x));
			}
		} else if (type.equals(LinkedList.class)) {
			LinkedList ll = (LinkedList) object;
			dos.writeInt(ll.size());
			synchronized (ll) {
				for (Iterator i = ll.iterator(); i.hasNext();) {
					writeToDataOutputStream(i.next(), dos);
				}
			}
		} else if (WritableToDataOutputStream.class.isAssignableFrom(type)) {
			WritableToDataOutputStream b = (WritableToDataOutputStream) object;
			b.writeToDataOutputStream(dos);
		} else {
			throw new RuntimeException("Unrecognised field type: " + type);
		}
	}
}