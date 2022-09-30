/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import static java.util.concurrent.TimeUnit.HOURS;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import freenet.support.CurrentTimeUTC;
import freenet.support.LRUMap;
import freenet.support.Logger;
import freenet.support.StringValidityChecker;

/**
 * A basic session manager for cookie-based HTTP session.
 * It allows its parent web interface to associate a "UserID" (a string) with a session ID.  
 * 
 * Formal definition of a SessionManager:
 * A 1:1 mapping of SessionID to UserID. Queries by SessionID and UserID run in O(1).
 * The Session ID primary key consists of: Cookie path + Cookie name + random "actual" session ID
 * The user ID is received from the client application.
 * 
 * Therefore, when multiple client applications want to store sessions, each one is supposed
 * to create its own SessionManager because user IDs might overlap.
 * 
 * The sessions of each application then get their {@link Session} by using a different
 * cookie path OR a different cookie namespace, depending on which constructor you use.
 * 
 * Paths are used when client applications do NOT share the same path on the server.
 * For example "/Chat" would cause the browser to only send back the cookie if the user is
 * browsing "/Chat/", not for "/". 
 * BUT usually we want the menu contents of client applications to be in the logged-in state even if 
 * the user is NOT browsing the client application web interface right now, therefore the "/" path 
 * must be used in most cases.
 * If client application cookies shall be received from all paths on the server, the client
 * application should use the constructor which requires a cookie namespace.
 * 
 * The usage of a namespace gurantees that Sessions of different client applications do not overlap.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class SessionManager {

	/**
	 * The amount of milliseconds after which a session is deleted due to expiration.
	 */
	public static final long MAX_SESSION_IDLE_TIME = HOURS.toMillis(1);
	
	public static final String SESSION_COOKIE_NAME = "SessionID"; 
	
	private final URI mCookiePath;
	private final String mCookieNamespace;
	private final String mCookieName;

	/**
	 * Constructs a new session manager for use with the given cookie path.
	 * Cookies are only sent back if the user is browsing the domain within that path.
	 * 
	 * @param myCookiePath The path in which the cookies should be valid.
	 */
	public SessionManager(URI myCookiePath) {
		if(myCookiePath.isAbsolute())
			throw new IllegalArgumentException("Illegal cookie path, must be relative: " + myCookiePath);
		
		if(myCookiePath.toString().startsWith("/") == false)
			throw new IllegalArgumentException("Illegal cookie path, must start with /: " + myCookiePath);
		
		// FIXME: The new constructor was written at 2010-11-15. Uncomment the following safety check after we gave plugins some time to migrate
		//	if(myCookiePath.getPath().equals("/"))
		//		throw new IllegalArgumentException("Illegal cookie path '/'. You should use the constructor which allows the specification" +
		//			"of a namespace for using the global path.");
		
		
		// TODO: Add further checks. 
		
		//mCookieDomain = myCookieDomain;
		mCookiePath = myCookiePath;
		mCookieNamespace = "";
		mCookieName = SESSION_COOKIE_NAME;
	}
	
	/**
	 * Constructs a new session manager for use with the "/" cookie path
	 * 
	 * @param myCookieNamespace The name of the client application which uses this cookie. Must not be empty. Must be latin letters and numbers only.
	 */
	public SessionManager(String myCookieNamespace) {
		if(myCookieNamespace.length() == 0)
			throw new IllegalArgumentException("You must specify a cookie namespace or use the constructor " +
					"which allows specification of a cookie path.");
		
		if(!StringValidityChecker.isLatinLettersAndNumbersOnly(myCookieNamespace))
			throw new IllegalArgumentException("The cookie namespace must be latin letters and numbers only.");
		
		//mCookieDomain = myCookieDomain;
		try {
			mCookiePath = new URI("/");
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		mCookieNamespace = myCookieNamespace;
		mCookieName = myCookieNamespace + SESSION_COOKIE_NAME;
	}
	
	
	public static final class Session {

		private final UUID mID;
		private final String mUserID;
		private final Map<String, Object> mAttributes = new HashMap<String, Object>();

		private long mExpiresAtTime;
		
		private Session(String myUserID, long currentTime) {
			mID = UUID.randomUUID();
			mUserID = myUserID;
			mExpiresAtTime = currentTime + SessionManager.MAX_SESSION_IDLE_TIME;
		}
		
		@Override
		public boolean equals(Object obj) {
			if(obj == null) return false;
			if(!(obj instanceof Session)) return false;
			Session other = ((Session)obj);
			return other.getID().equals(mID);
		}
		
		@Override
		public int hashCode() {
			return mID.hashCode();
		}

		public UUID getID() {
			return mID;
		}
		
		public String getUserID() {
			return mUserID;
		}
		
		private long getExpirationTime() {
			return mExpiresAtTime;
		}
		
		private boolean isExpired(long time) {
			return time >= mExpiresAtTime;
		}

		private void updateExpiresAtTime(long currentTime) {
			mExpiresAtTime = currentTime + SessionManager.MAX_SESSION_IDLE_TIME;
		}

		/**
		 * Returns whether this session contains an attribute with the given
		 * name.
		 *
		 * @param name
		 *            The name of the attribute to check for
		 * @return {@code true} if this session contains an attribute with the
		 *         given name, {@code false} otherwise
		 */
		public boolean hasAttribute(String name) {
			return mAttributes.containsKey(name);
		}

		/**
		 * Returns the value of the attribute with the given name. If there is
		 * no attribute with the given name, {@code null} is returned.
		 *
		 * @param name
		 *            The name of the attribute whose value to get
		 * @return The value of the attribute, or {@code null}
		 */
		public Object getAttribute(String name) {
			return mAttributes.get(name);
		}

		/**
		 * Sets the value of the attribute with the given name.
		 *
		 * @param name
		 *            The name of the attribute whose value to set
		 * @param value
		 *            The new value of the attribute
		 */
		public void setAttribute(String name, Object value) {
			mAttributes.put(name, value);
		}

		/**
		 * Removes the attribute with the given name. Nothing will happen if
		 * there is no attribute with the given name.
		 *
		 * @param name
		 *            The name of the attribute to remove
		 */
		public void removeAttribute(String name) {
			mAttributes.remove(name);
		}

		/**
		 * Returns the names of all currently existing attributes.
		 *
		 * @return The names of all attributes
		 */
		public Set<String> getAttributeNames() {
			return mAttributes.keySet();
		}

	}

	private final LRUMap<UUID, Session> mSessionsByID = new LRUMap<UUID, Session>();
	private final Hashtable<String, Session> mSessionsByUserID = new Hashtable<String, Session>();
	
	
	/**
	 * Returns the cookie path as specified in the constructor.
	 * Returns "/" if the constructor which only requires a namespace was used.
	 */
	public URI getCookiePath() {
		return mCookiePath;
	}
	
	
	/**
	 * Returns the namespace as specified in the constructor.
	 * Returns an empty string if the constructor which requires a cookie path only was used.
	 */
	public String getCookieNamespace() {
		return mCookieNamespace;
	}
		
	
	/**
	 * Creates a new session for the given user ID.
	 * 
	 * If a session for the given user ID already exists, it is deleted. It is not re-used to ensure that parallel logins with the same user account from 
	 * different computers do not work.
	 *
	 * @param context The ToadletContext in which the session cookie shall be stored.
	 */
	public synchronized Session createSession(String userID, ToadletContext context) {
		// We must synchronize around the fetching of the time and mSessionsByID.push() because mSessionsByID is no sorting data structure: It's a plain
		// LRUMap so to ensure that it stays sorted the operation "getTime(); push();" must be atomic.
		long time = CurrentTimeUTC.getInMillis();
		
		removeExpiredSessions(time);
		
		deleteSessionByUserID(userID);
		
		Session session = new Session(userID, time);
		mSessionsByID.push(session.getID(), session);
		mSessionsByUserID.put(session.getUserID(), session);
		
		setSessionCookie(session, context);
		
		return session;
	}
	
	/** 
	 * Returns true if the given {@link ToadletContext} contains a session cookie for a valid (existing and not expired) session.
	 * 
	 * In opposite to {@link getSessionUserID}, this function does NOT extend the validity of the session.
	 * Therefore, this function can be considered as a way of peeking for a session, to decide which Toadlet links should be visible.
	 */
	public synchronized boolean sessionExists(ToadletContext context) {
		UUID sessionID = getSessionID(context);
		
		if(sessionID == null)
			return false;
		
		removeExpiredSessions(CurrentTimeUTC.getInMillis());
		
		return mSessionsByID.containsKey(sessionID);
	}
	
	/**
	 * Retrieves the session ID from the session cookie in the given {@link ToadletContext}, checks if it contains a valid (existing and not expired) session
	 * and if yes, returns the {@link Session}. 
	 * 
	 * If the session was valid, then its validity is extended by {@link MAX_SESSION_IDLE_TIME}.
	 * 
	 * If the session did not exist or is not valid anymore, <code>null</code> is returned.
	 */
	public synchronized Session useSession(ToadletContext context) {
		UUID sessionID = getSessionID(context);
		if(sessionID == null)
			return null;
		
		// We must synchronize around the fetching of the time and mSessionsByID.push() because mSessionsByID is no sorting data structure: It's a plain
		// LRUMap so to ensure that it stays sorted the operation "getTime(); push();" must be atomic.
		long time = CurrentTimeUTC.getInMillis();
		
		removeExpiredSessions(time);
		
		Session session = mSessionsByID.get(sessionID);
		
		if(session == null)
			return null;
		
		
		session.updateExpiresAtTime(time);
		mSessionsByID.push(session.getID(), session);
		
		setSessionCookie(session, context);
		
		return session;
	}

	/**
	 * Retrieves the session ID from the session cookie in the given {@link ToadletContext}, checks if it contains a valid (existing and not expired) session
	 * and if yes, deletes the session.
	 * 
	 * @return True if the session was deleted, false if there was no session cookie or no session.
	 */
	public boolean deleteSession(ToadletContext context) {
		UUID sessionID = getSessionID(context);
		if(sessionID == null)
			return false;
		
		return deleteSession(sessionID);
	}
	
	/**
	 * @return Returns the session ID stored in the cookies of the HTTP headers of the given {@link ToadletContext}. Returns null if there is no session ID stored.
	 */
	private UUID getSessionID(ToadletContext context) {
		if(context == null)
			return null;
		
		try {
			ReceivedCookie sessionCookie = context.getCookie(null, mCookiePath, mCookieName);
			
			return sessionCookie == null ? null : UUID.fromString(sessionCookie.getValue());
		} catch(ParseException e) {
			Logger.error(this, "Getting session cookie failed", e);
			return null;
		} catch(IllegalArgumentException e) {
			Logger.error(this, "Getting the value of the session cookie failed", e);
			return null;
		}
	}
	
	/**
	 * Stores a session cookie for the given session in the given {@link ToadletContext}'s HTTP headers.
	 * @param session
	 * @param context
	 */
	private void setSessionCookie(Session session, ToadletContext context) {
		context.setCookie(new Cookie(mCookiePath, mCookieName, session.getID().toString(), new Date(session.getExpirationTime())));
	}
	
	/**
	 * Deletes the session with the given ID.
	 * 
	 * @return True if a session with the given ID existed.
	 */
	private synchronized boolean deleteSession(UUID sessionID) {
		Session session = mSessionsByID.get(sessionID);

		if(session == null)
			return false;
		
		mSessionsByID.removeKey(sessionID);
		mSessionsByUserID.remove(session.getUserID());
		return true;
	}
	
	/**
	 * Deletes the session associated with the given user ID.
	 * 
	 * @return True if a session with the given ID existed.
	 */
	private synchronized boolean deleteSessionByUserID(String userID) {
		Session session = mSessionsByUserID.remove(userID);
		if(session == null)
			return false;
		
		mSessionsByID.removeKey(session.getID());
		return true;
	}
	
	/**
	 * Garbage-collects any expired sessions. Must be called before client-inteface functions do anything which relies on the existence a session,
	 * that is: creating sessions, using sessions or checking whether sessions exist.
	 * 
	 * FIXME: Before putting the session manager into fred, write a thread which periodically garbage collects old sessions - currently, sessions
	 * will only be garbage collected if any client continues using the SessiomManager
	 * 
	 * @param time The current time.
	 */
	private synchronized void removeExpiredSessions(long time) {
		for(Session session = mSessionsByID.peekValue(); session != null && session.isExpired(time); session = mSessionsByID.peekValue()) {
			mSessionsByID.popValue();
			mSessionsByUserID.remove(session.getUserID());
		}
		
		// FIXME: Execute every few hours only.
		verifySessionsByUserIDTable();
	}
	
	/**
	 * Debug function which checks whether the sessions by user ID table does not contain any sessions which do not exist anymore;
	 */
	private synchronized void verifySessionsByUserIDTable() {
		
		Enumeration<Session> sessions = mSessionsByUserID.elements();
		while(sessions.hasMoreElements()) {
			Session session = sessions.nextElement();
			
			if(mSessionsByID.containsKey(session.getID()) == false) {
				Logger.error(this, "Sessions by user ID hashtable contains deleted session, removing it: " + session);
				
				mSessionsByUserID.remove(session.getUserID());
			}
		}
	}

}
