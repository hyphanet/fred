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
    private Vector _fieldList = new Vector(1,1);
    PeerContext _source;
    private long _timeout;
    /** If true, timeouts are relative to the start of waiting, if false, they are relative to
     * the time of calling setTimeout() */
    private boolean _timeoutFromWait;
    private int _initialTimeout;
    private MessageFilter _or;
    private Message _message;
    private boolean _matchesDroppedConnections;
    private boolean _matchesRestartedConnections;
    private AsyncMessageFilterCallback _callback;

    private MessageFilter() {
        setTimeout(DEFAULT_TIMEOUT);
        _matchesDroppedConnections = true; // on by default
        _matchesRestartedConnections = true; // also on by default
        _timeoutFromWait = true;
    }

    public static MessageFilter create() {
        return new MessageFilter();
    }

    void onStartWaiting() {
    	synchronized(this) {
    		if(_initialTimeout > 0 && _timeoutFromWait)
    			_timeout = System.currentTimeMillis() + _initialTimeout;
    	}
    	if(_or != null)
    		_or.onStartWaiting();
    }
    
    /**
     * Set whether the timeout is relative to the creation of the filter, or the start of
     * waitFor().
     * @param b If true, the timeout is relative to the time at which setTimeout() was called,
     * if false, it's relative to the start of waitFor().
     */
    public MessageFilter setTimeoutRelativeToCreation(boolean b) {
    	_timeoutFromWait = !b;
    	return this;
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

	public MessageFilter setNoTimeout() {
		_timeout = Long.MAX_VALUE;
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
			if(_fields.put(fieldName, fieldValue) == null)
				_fieldList.add(fieldName);
		}
		return this;
	}

	public MessageFilter or(MessageFilter or) {
		if((or != null) && (_or != null) && or != _or) {
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
	
	public MessageFilter setMatchesRestartedConnections(boolean m) {
		_matchesRestartedConnections = m;
		return this;
	}
	
	public MessageFilter setAsyncCallback(AsyncMessageFilterCallback cb) {
		_callback = cb;
		return this;
	}
	
	public boolean match(Message m) {
		if ((_or != null) && (_or.match(m))) {
			_matched = true;
			return true;
		}
		if ((_type != null) && (!_type.equals(m.getSpec()))) {
			return false;
		}
		if ((_source != null) && (!_source.equals(m.getSource()))) {
			return false;
		}
		synchronized (_fields) {
			for (int i = 0; i < _fieldList.size(); i++) {
				String fieldName = (String) _fieldList.get(i);
				if (!m.isSet(fieldName)) {
					return false;
				}
				if (!_fields.get(fieldName).equals(m.getFromPayload(fieldName))) {
					return false;
				}
			}
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
		if(_callback != null && _callback.shouldTimeout())
			_timeout = -1; // timeout immediately
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
    
    public boolean matchesDroppedConnection(PeerContext ctx) {
        return _matchesDroppedConnections && _source == ctx;
    }
    
    public boolean matchesRestartedConnection(PeerContext ctx) {
    	return _matchesRestartedConnections && _source == ctx;
    }
    
    /**
     * Notify because of a dropped connection.
     * Caller must verify _matchesDroppedConnection and _source.
     * @param ctx
     */
    public synchronized void onDroppedConnection(PeerContext ctx) {
   		notifyAll();
    }

    /**
     * Notify because of a restarted connection.
     * Caller must verify _matchesDroppedConnection and _source.
     * @param ctx
     */
    public synchronized void onRestartedConnection(PeerContext ctx) {
   		notifyAll();
    }

    /**
     * Notify waiters that we have been matched.
     * Hopefully no locks will be held at this point by the caller.
     */
	public void onMatched() {
		if(_callback != null) {
			_callback.onMatched(_message);
		}
		synchronized(this) {
			notifyAll();
		}
	}

	/**
	 * Notify waiters that we have timed out.
	 */
	public synchronized void onTimedOut() {
		notifyAll();
	}
}
