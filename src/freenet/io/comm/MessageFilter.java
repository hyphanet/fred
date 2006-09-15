/*
  MessageFilter.java / Freenet, Dijjer - A Peer to Peer HTTP Cache
  Copyright (C) 2004,2005 Change.Tv, Inc
  Copyright (C) Ian
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.io.comm;

import java.util.*;

import freenet.support.Logger;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class MessageFilter {

    public static final String VERSION = "$Id: MessageFilter.java,v 1.7 2005/08/25 17:28:19 amphibian Exp $";

    private static final int DEFAULT_TIMEOUT = 10000;
    private boolean _matched;
    private PeerContext _droppedConnection;
	private MessageType _type;
    private HashMap _fields = new HashMap();
    PeerContext _source;
    private long _timeout;
    private int _initialTimeout;
    private MessageFilter _or;
    private Message _message;
    private boolean _matchesDroppedConnections;

    private MessageFilter() {
        setTimeout(DEFAULT_TIMEOUT);
        _matchesDroppedConnections = true; // on by default
    }

    public static MessageFilter create() {
        return new MessageFilter();
    }

    /**
     * This filter will expire after the specificed amount of time. Note also that where two or more filters match the
     * same message, the one with the nearer expiry time will get priority
     *
     * @param timeout The time before this filter expires in ms
     * @return This message filter
     */
	public MessageFilter setTimeout(int timeout) {
		_initialTimeout = timeout;
		_timeout = System.currentTimeMillis() + timeout;
		return this;
	}

	public MessageFilter setType(MessageType type) {
		_type = type;
		return this;
	}

	public MessageFilter setSource(PeerContext source) {
		_source = source;
		return this;
	}

	public MessageFilter setField(String fieldName, boolean value) {
		return setField(fieldName, Boolean.valueOf(value));
	}

	public MessageFilter setField(String fieldName, byte value) {
		return setField(fieldName, new Byte(value));
	}

	public MessageFilter setField(String fieldName, short value) {
		return setField(fieldName, new Short(value));
	}

	public MessageFilter setField(String fieldName, int value) {
		return setField(fieldName, new Integer(value));
	}

	public MessageFilter setField(String fieldName, long value) {
		return setField(fieldName, new Long(value));
	}

	public MessageFilter setField(String fieldName, Object fieldValue) {
		if ((_type != null) && (!_type.checkType(fieldName, fieldValue))) {
			throw new IncorrectTypeException("Got " + fieldValue.getClass() + ", expected " + _type.typeOf(fieldName) + " for " + _type.getName());
		}
		synchronized (_fields) {
			_fields.put(fieldName, fieldValue);
		}
		return this;
	}

	public MessageFilter or(MessageFilter or) {
		if((or != null) && (_or != null)) {
			// FIXME maybe throw? this is almost certainly a bug, and a nasty one too!
			Logger.error(this, "or() replacement: "+_or+" -> "+or, new Exception("error"));
		}
		_or = or;
		return this;
	}

	public MessageFilter setMatchesDroppedConnection(boolean m) {
	    _matchesDroppedConnections = m;
	    return this;
	}
	
	public boolean match(Message m) {
		if ((_or != null) && (_or.match(m))) {
			_matched = true;
			return true;
		}
		if (_type != null) {
			if (!m.getSpec().equals(_type)) {
				return false;
			}
		}
		synchronized (_fields) {
			for (Iterator iter = _fields.keySet().iterator(); iter.hasNext();) {
				String fieldName = (String) iter.next();
				if (!m.isSet(fieldName)) {
					return false;
				}
				if (!_fields.get(fieldName).equals(m.getFromPayload(fieldName))) {
					return false;
				}
			}
		}
		if ((_source != null) && (!_source.equals(m.getSource()))) {
			return false;
		}
		_matched=true;
		return true;
	}

	public boolean matched() {
		return _matched;
	}

	public PeerContext droppedConnection() {
	    return _droppedConnection;
	}
	
	public boolean timedOut() {
		return _timeout < System.currentTimeMillis();
	}

    public Message getMessage() {
        return _message;
    }

    public void setMessage(Message message) {
        //Logger.debug(this, "setMessage("+message+") on "+this, new Exception("debug"));
        _message = message;
    }

    public int getInitialTimeout() {
        return _initialTimeout;
    }
    
    public long getTimeout() {
        return _timeout;
    }

    public String toString() {
    	return _type.getName();
    }

    public void clearMatched() {
        _matched = false;
        _message = null;
    }

    public void clearOr() {
        _or = null;
    }
    
    public boolean matchesDroppedConnection() {
        return _matchesDroppedConnections;
    }
    
    public void onDroppedConnection(PeerContext ctx) {
        if(_matchesDroppedConnections && (_source == ctx)) {
            _droppedConnection = ctx;
        }
    }
}
