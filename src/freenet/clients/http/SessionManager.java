/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.text.ParseException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.UUID;

import freenet.support.CurrentTimeUTC;
import freenet.support.LRUHashtable;
import freenet.support.Logger;

/**
 * A basic session manager for cookie-based HTTP session.
 * It allows its parent web interface to associate a "UserID" (a string) with a session ID.  
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class SessionManager {

	/**
	 * The amount of milliseconds after which a session is deleted due to expiration.
	 */
	public static final long MAX_SESSION_IDLE_TIME = 1 * 60 * 60 * 1000;
	
	public static final String SESSION_COOKIE_NAME = "SessionID"; 
	
	private final URI mCookiePath;
	private URI mLogInRedirectURI;

	/**
	 * Constructs a new session manager.
	 * 
	 * @param myCookiePath The path in which the cookies should be valid 
	 */
	public SessionManager(URI myCookiePath) {
		if(myCookiePath.isAbsolute())
			throw new IllegalArgumentException("Illegal cookie path, must be relative: " + myCookiePath);
		
		if(myCookiePath.toString().startsWith("/") == false)
			throw new IllegalArgumentException("Illegal cookie path, must start with /: " + myCookiePath);
		
		// TODO: Add further checks. 
		
		//mCookieDomain = myCookieDomain;
		mCookiePath = myCookiePath;
		mLogInRedirectURI = null;
	}
	
	/**
	 * Constructs a new session manager.
	 * @param myCookiePath The path in which the cookies should be valid
	 * @param myLogInRedirectURI the URI to which we should redirect if the session is invalid
	 * @deprecated PluginRespirator stores SessionManagers that can be used for
	 * 	setting common sessions. Using this with the same myCookiePath will overwrite
	 * 	other sessions.
	 */
	@Deprecated
	public SessionManager(URI myCookiePath, URI myLogInRedirectURI) {
		this(myCookiePath);
		mLogInRedirectURI = myLogInRedirectURI;
	}
	
	public static final class Session {

		private final UUID mID;
		private final String mUserID;
		
		private long mExpiresAtTime;
		
		private Session(String myUserID, long currentTime) {
			mID = UUID.randomUUID();
			mUserID = myUserID;
			mExpiresAtTime = currentTime + SessionManager.MAX_SESSION_IDLE_TIME;
		}
		
		public boolean equals(Object obj) {
			Session other = ((Session)obj);
			
			return other.getID().equals(mID);
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
	}

	private final LRUHashtable<UUID, Session> mSessionsByID = new LRUHashtable<UUID, Session>();
	private final Hashtable<String, Session> mSessionsByUserID = new Hashtable<String, Session>();
	
	
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
		// LRUHashtable so to ensure that it stays sorted the operation "getTime(); push();" must be atomic.
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
	 * If the session is not valid anymore, <code>null</code> is returned if the
	 * new constructor was used (for example by PluginRespirator). If the deprecated
	 * constructor was used, a RedirectException to the login URI is thrown.
	 * @throws RedirectException if login redirect URI was set
	 */
	public synchronized Session useSession(ToadletContext context) throws RedirectException {
		UUID sessionID = getSessionID(context);
		if(sessionID == null) {
			if (mLogInRedirectURI == null) return null;
			throw new RedirectException(mLogInRedirectURI);
		}
		
		// We must synchronize around the fetching of the time and mSessionsByID.push() because mSessionsByID is no sorting data structure: It's a plain
		// LRUHashtable so to ensure that it stays sorted the operation "getTime(); push();" must be atomic.
		long time = CurrentTimeUTC.getInMillis();
		
		removeExpiredSessions(time);
		
		Session session = mSessionsByID.get(sessionID);
		
		if(session == null) {
			if (mLogInRedirectURI == null) return null;
			throw new RedirectException(mLogInRedirectURI);
		}
		
		session.updateExpiresAtTime(time);
		mSessionsByID.push(session.getID(), session);
		
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
			ReceivedCookie sessionCookie = context.getCookie(null, mCookiePath, SESSION_COOKIE_NAME);
			
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
		context.setCookie(new Cookie(mCookiePath, SESSION_COOKIE_NAME, session.getID().toString(), new Date(session.getExpirationTime())));
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
		verifyQueueOrder();
		verifySessionsByUserIDTable();
	}
	
	/**
	 * Debug function which checks whether the session LRU queue is in order;
	 */
	private synchronized void verifyQueueOrder() {
		long previousTime = 0;
		
		Enumeration<Session> sessions = mSessionsByID.values();
		while(sessions.hasMoreElements()) {
			Session session = sessions.nextElement();
			
			if(session.getExpirationTime() < previousTime) {
				long sessionAge = (CurrentTimeUTC.getInMillis() - session.getExpirationTime()) / (60 * 60 * 1000);
				Logger.error(this, "Session LRU queue out of order! Found session which is " + sessionAge + " hour old: " + session); 
				Logger.error(this, "Deleting all sessions...");
				
				mSessionsByID.clear();
				mSessionsByUserID.clear();
				return;
			}
		}
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