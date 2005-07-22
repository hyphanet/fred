/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.io.*;
import java.net.*;
import java.util.*;

import freenet.support.Logger;
import freenet.support.Serializer;

/**
 * A Message which can be read from and written to a DatagramPacket
 *
 * @author ian
 */
public class Message {

    public static final String VERSION = "$Id: Message.java,v 1.6 2005/07/22 12:15:45 amphibian Exp $";

	private final MessageType _spec;
	private final PeerContext _source;
	private final HashMap _payload = new HashMap();

	public static Message decodeFromPacket(byte[] buf, int offset, int length, PeerContext peer) {
		DataInputStream dis
	    = new DataInputStream(new ByteArrayInputStream(buf,
	        offset, length));
		MessageType mspec;
        try {
            mspec = MessageType.getSpec(new Integer(dis.readInt()));
        } catch (IOException e1) {
            Logger.minor(Message.class,"Failed to read message type: "+e1, e1);
            return null;
        }
        if (mspec == null) {
		    return null;
		}
		if(mspec.isInternalOnly())
		    return null; // silently discard internal-only messages
		Message m = new Message(mspec, peer);
		try {
		    for (Iterator i = mspec.getOrderedFields().iterator(); i.hasNext();) {
		        String name = (String) i.next();
		        Class type = (Class) mspec.getFields().get(name);
		        if (type.equals(LinkedList.class)) { // Special handling for LinkedList to deal with element type
		            m.set(name, Serializer.readListFromDataInputStream((Class) mspec.getLinkedListTypes().get(name), dis));
		        } else {
		            m.set(name, Serializer.readFromDataInputStream(type, dis));
		        }
		    }
		} catch (EOFException e) {
		    Logger.normal(Message.class,"Message packet ends prematurely while deserialising "+mspec.getName());
		    return null;
		} catch (IOException e) {
		    Logger.error(Message.class, "WTF?: "+e+" reading from buffer stream", e);
		    return null;
		}
		return m;
	}
	
	public Message(MessageType spec) {
		this(spec, null);
	}

	private Message(MessageType spec, PeerContext source) {
		_spec = spec;
		_source = source;
	}

	public boolean getBoolean(String key) {
		return ((Boolean) _payload.get(key)).booleanValue();
	}

	public byte getByte(String key) {
		return ((Byte) _payload.get(key)).byteValue();
	}

	public short getShort(String key) {
		return ((Short) _payload.get(key)).shortValue();
	}

	public int getInt(String key) {
		return ((Integer) _payload.get(key)).intValue();
	}

	public long getLong(String key) {
		return ((Long) _payload.get(key)).longValue();
	}

	public double getDouble(String key) {
	    return ((Double) _payload.get(key)).doubleValue();
	}
	
	public String getString(String key) {
		return (String)_payload.get(key);
	}

	public Object getObject(String key) {
		return _payload.get(key);
	}

	public void set(String key, boolean b) {
		set(key, new Boolean(b));
	}

	public void set(String key, byte b) {
		set(key, new Byte(b));
	}

	public void set(String key, short s) {
		set(key, new Short(s));
	}

	public void set(String key, int i) {
		set(key, new Integer(i));
	}

	public void set(String key, long l) {
		set(key, new Long(l));
	}

    public void set(String key, double d) {
        set(key, new Double(d));
    }
    
	public void set(String key, Object value) {
		if (!_spec.checkType(key, value)) {
			if (value == null) {
				throw new IncorrectTypeException("Got null for " + key);				
			}
			throw new IncorrectTypeException("Got " + value.getClass() + ", expected " + _spec.typeOf(key));
		}
		_payload.put(key, value);
	}

	public byte[] encodeToPacket(LowLevelFilter f, PeerContext destination) {
//		if (this.getSpec() != MessageTypes.ping && this.getSpec() != MessageTypes.pong)
//		Logger.logMinor("<<<<< Send message : " + this);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try {
			dos.writeInt(_spec.getName().hashCode());
			for (Iterator i = _spec.getOrderedFields().iterator(); i.hasNext();) {
				String name = (String) i.next();
				Serializer.writeToDataOutputStream(_payload.get(name), dos);
			}
			dos.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return baos.toByteArray();
	}

	public String toString() {
		StringBuffer ret = new StringBuffer(1000);
		String comma = "";
		ret.append(_spec.getName() + " {");
		for (Iterator i = _spec.getFields().keySet().iterator(); i.hasNext();) {
			ret.append(comma);
			String name = (String) i.next();
			ret.append(name + "=" + _payload.get(name) );
			comma = ", ";
		}
		ret.append("}");
		return ret.toString();
	}

	public PeerContext getSource() {
		return _source;
	}

	public boolean isInternal() {
	    return _source == null;
	}
	
	public MessageType getSpec() {
		return _spec;
	}

	public boolean isSet(String fieldName) {
		return _payload.containsKey(fieldName);
	}
	
	public Object getFromPayload(String fieldName) throws FieldNotSetException {
		Object r =  _payload.get(fieldName);
		if (r == null) {
			throw new FieldNotSetException(fieldName+" not set");
		}
		return r;
	}
	
	public static class FieldNotSetException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		
		public FieldNotSetException(String message) {
			super(message);
		}
	}
}