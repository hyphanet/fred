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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import freenet.support.Logger;
import freenet.support.Serializer;
import freenet.support.ShortBuffer;

public class MessageType {

    public static final String VERSION = "$Id: MessageType.java,v 1.6 2005/08/25 17:28:19 amphibian Exp $";

	private static HashMap<Integer, MessageType> _specs = new HashMap<Integer, MessageType>();

	private final String _name;
	private final LinkedList<String> _orderedFields = new LinkedList<String>();
	private final HashMap<String, Class<?>> _fields = new HashMap<String, Class<?>>();
	private final HashMap<String, Class<?>> _linkedListTypes = new HashMap<String, Class<?>>();
	private final boolean internalOnly;
	private final short priority;
	private final boolean isLossyPacketMessage;

	public MessageType(String name, short priority) {
	    this(name, priority, false, false);
	}
	
	public MessageType(String name, short priority, boolean internal, boolean isLossyPacketMessage) {
		_name = name;
		this.priority = priority;
		this.isLossyPacketMessage = isLossyPacketMessage;
		internalOnly = internal;
		// XXX hashCode() is NOT required to be unique!
		Integer id = Integer.valueOf(name.hashCode());
		if (_specs.containsKey(id)) {
			throw new RuntimeException("A message type by the name of " + name + " already exists!");
		}
		_specs.put(id, this);
	}

	public void unregister() {
		_specs.remove(Integer.valueOf(_name.hashCode()));
	}
	
	public void addLinkedListField(String name, Class<?> parameter) {
		_linkedListTypes.put(name, parameter);
		addField(name, LinkedList.class);
	}

	public void addField(String name, Class<?> type) {
		_fields.put(name, type);
		_orderedFields.addLast(name);
	}
	
	public void addRoutedToNodeMessageFields() {
        addField(DMT.UID, Long.class);
        addField(DMT.TARGET_LOCATION, Double.class);
        addField(DMT.HTL, Short.class);
        addField(DMT.NODE_IDENTITY, ShortBuffer.class);
	}

	public boolean checkType(String fieldName, Object fieldValue) {
		if (fieldValue == null) {
			return false;
		}
		Class<?> defClass = _fields.get(fieldName);
		if (defClass == null) {
			throw new IllegalStateException("Cannot set field \"" + fieldName + "\" which is not defined" +
			                                " in the message type \"" + getName() + "\".");
		}
		Class<?> valueClass = fieldValue.getClass();
		if(defClass == valueClass) return true;
		if(defClass.isAssignableFrom(valueClass)) return true;
		return false;
	}

	public Class<?> typeOf(String field) {
		return _fields.get(field);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof MessageType)) {
			return false;
		}
		// We can only register one MessageType for each name.
		// So we can do == here.
		return ((MessageType) o)._name == _name;
	}

	@Override
	public int hashCode() {
	    return _name.hashCode();
	}
	
	public static MessageType getSpec(Integer specID, boolean dontLog) {
		MessageType id = _specs.get(specID);
		if (id == null) {
			if(!dontLog)
				Logger.error(MessageType.class, "Unrecognised message type received (" + specID + ')');
		}
		return id;
	}

	public String getName() {
		return _name;
	}

	public Map<String, Class<?>> getFields() {
		return _fields;
	}

	public LinkedList<String> getOrderedFields() {
		return _orderedFields;
	}
	
	public Map<String, Class<?>> getLinkedListTypes() {
		return _linkedListTypes;
	}

    /**
     * @return True if this message is internal-only.
     * If this is the case, any incoming messages in UDP form of this
     * spec will be silently discarded.
     */
    public boolean isInternalOnly() {
        return internalOnly;
    }
	
    /** @return The default priority for the message type. Messages's don't necessarily
     * use this: Message.boostPriority() can increase it for a realtime message, for 
     * instance. */
	public short getDefaultPriority() {
		return priority;
	}

	/** Only works for simple messages!! */
	public int getMaxSize(int maxStringLength) {
		// This method mirrors Message.encodeToPacket.
		int length = 0;
		length += 4; // _spec.getName().hashCode()
		for (Map.Entry<String, Class<?>> entry : _fields.entrySet()) {
			length += Serializer.length(entry.getValue(), maxStringLength);
		}
		return length;
	}

	public boolean isLossyPacketMessage() {
		return isLossyPacketMessage;
	}
}