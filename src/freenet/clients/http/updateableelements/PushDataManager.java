package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import freenet.support.Logger;
import freenet.support.Ticker;

/** A manager class that manages all the pushing. All it's public method must be synchronized to maintain consistency. */
public class PushDataManager {

	private static volatile boolean						logMINOR;

	static {
		Logger.registerClass(PushDataManager.class);
	}

	/** What notifications are waiting for the leader */
	private Map<String, List<UpdateEvent>>				awaitingNotifications	= new HashMap<String, List<UpdateEvent>>();

	/** What elements are on the page */
	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	/** What pages are on the element. It is redundant with the pages map. */
	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	/** Stores whether a keepalive was received for a request since the Cleaner last run */
	private Map<String, Boolean>						isKeepaliveReceived		= new HashMap<String, Boolean>();
	
	private Map<String, Boolean>						isFirstKeepaliveReceived		= new HashMap<String, Boolean>();

	/** The Cleaner that runs periodically and cleanes the failing requests */
	private Ticker										cleaner;

	/** A task for the Cleaner that the Cleaner invokes */
	private CleanerTimerTask							cleanerTask				= new CleanerTimerTask();

	/** The Cleaner only runs when needed. If this field is true, then the Cleaner is scheduled to run */
	private boolean										isScheduled				= false;

	public PushDataManager(Ticker ticker) {
		cleaner = ticker;
	}

	/**
	 * An element is updated and needs to be pushed to all requests.
	 * 
	 * @param id
	 *            - The id of the element that changed
	 */
	public synchronized void updateElement(String id) {
		if (logMINOR) {
			Logger.minor(this, "Element updated id:" + id);
		}
		boolean needsUpdate = false;
		if(elements.containsKey(id)==false){
			if(logMINOR){
				Logger.minor(this, "Element is updating, but not present on elements! elements:"+elements+" pages:"+pages+" awaitingNotifications:"+awaitingNotifications);
			}
		}
		if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
			if(logMINOR){
				Logger.minor(this, "Element is present on page:"+reqId+". Adding an UpdateEvent for all notification list.");
			}
			for(Map.Entry<String, List<UpdateEvent>> entry : awaitingNotifications.entrySet()) {
//			for (List<UpdateEvent> notificationList : awaitingNotifications.values()) {
				List<UpdateEvent> notificationList = entry.getValue();
				UpdateEvent updateEvent = new UpdateEvent(reqId, id);
				if (notificationList.contains(updateEvent) == false) {
					notificationList.add(updateEvent);
					if (logMINOR) {
						Logger.minor(this, "Notification("+updateEvent+") added to a notification list for "+entry.getKey());
					}
				} else {
					if (logMINOR)
						Logger.minor(this, "Not notifying "+entry.getKey()+" because already on list");
				}
			}
			needsUpdate = true;
		}
		if (needsUpdate) {
			if(logMINOR){
				Logger.minor(this, "Waking up notification polls");
			}
			notifyAll();
		}
	}

	/**
	 * A pushed element is rendered and needs to be tracked.
	 * 
	 * @param requestUniqueId
	 *            - The requestId that rendered the element
	 * @param element
	 *            - The element that is rendered
	 */
	public synchronized void elementRendered(String requestUniqueId, BaseUpdateableElement element) {
		if(logMINOR){
			Logger.minor(this, "Element is rendered in page:"+requestUniqueId+" element:"+element);
		}
		// Add to the pages
		if (pages.containsKey(requestUniqueId) == false) {
			pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
		}
		pages.get(requestUniqueId).add(element);
		// Add to the elements
		String id = element.getUpdaterId(requestUniqueId);
		if (elements.containsKey(id) == false) {
			elements.put(id, new ArrayList<String>());
		}
		elements.get(id).add(requestUniqueId);
		// The request needs to be tracked
		isKeepaliveReceived.put(requestUniqueId, true);

		if (awaitingNotifications.containsKey(requestUniqueId) == false) {
			awaitingNotifications.put(requestUniqueId, new ArrayList<UpdateEvent>());
		}
		// If the Cleaner isn't running, then we schedule it to clear this request if failing
		if (isScheduled == false) {
			if (logMINOR) {
				Logger.minor(this, "Cleaner is queued(1) time:" + System.currentTimeMillis());
			}
			cleaner.queueTimedJob(cleanerTask, "cleanerTask", getDelayInMs(), false, true);
			isScheduled = true;
		}
	}

	/**
	 * Returns the element's current state.
	 * 
	 * @param requestId
	 *            - The requestId that needs the element.
	 * @param id
	 *            - The element's id
	 */
	public synchronized BaseUpdateableElement getRenderedElement(String requestId, String id) {
		if(logMINOR){
			Logger.minor(this, "Getting element data for element:"+id+" in page:"+requestId);
		}
		if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
			if (element.getUpdaterId(requestId).compareTo(id) == 0) {
				element.updateState(false);
				return element;
			}
		}
		Logger.error(this, "Could not find data for the element requested. requestId:"+requestId+" id:"+id+" pages:"+pages+" keepaliveReceived:"+isKeepaliveReceived);
		return null;
	}

	/**
	 * Fails a request and copies all notifications directed to it to another request. It is invoked when a leadership change occurs.
	 * 
	 * @param originalRequestId
	 *            - The failing leader's id
	 * @param newRequestId
	 *            - The new leader's id
	 * @return Was the failover successful?
	 */
	public synchronized boolean failover(String originalRequestId, String newRequestId) {
		if (logMINOR) {
			Logger.minor(this, "Failover, original:" + originalRequestId + " new:" + newRequestId);
		}
		if (awaitingNotifications.containsKey(originalRequestId)) {
			awaitingNotifications.put(newRequestId, awaitingNotifications.remove(originalRequestId));
			if (logMINOR) {
				Logger.minor(this, "copied " + awaitingNotifications.get(newRequestId).size() + " notification:" + awaitingNotifications.get(newRequestId));
			}
			notifyAll();
			return true;
		} else {
			if (logMINOR) {
				Logger.minor(this, "Does not contains key");
			}
			return false;
		}
	}

	/**
	 * The request leaves, so it needs to be deleted
	 * 
	 * @param requestId
	 *            - The id of the request that is leaving
	 * @return Was a request deleted?
	 */
	public synchronized boolean leaving(String requestId) {
		return deleteRequest(requestId);
	}

	/**
	 * A keepalive received.
	 * 
	 * @param requestId
	 *            - The id of the request that sent the keepalive
	 * @return Was it successful?
	 */
	public synchronized boolean keepAliveReceived(String requestId) {
		if(logMINOR){
			Logger.minor(this, "Keepalive is received for page:"+requestId);
		}
		// If the request is already deleted, then fail
		if (isKeepaliveReceived.containsKey(requestId) == false) {
			if(logMINOR){
				Logger.minor(this, "Keepalive failed");
			}
			return false;
		}
		isKeepaliveReceived.put(requestId, true);
		isFirstKeepaliveReceived.put(requestId, true);
		notifyAll();
		return true;
	}

	/**
	 * Waits and return the next notification. Calling this method setup the notification list.
	 * 
	 * @param requestId
	 *            - The id of the request
	 * @return The next notification when present
	 */
	public synchronized UpdateEvent getNextNotification(String requestId) {
		if (logMINOR) {
			Logger.minor(this, "Polling for notification:" + requestId);
		}
		while (awaitingNotifications.get(requestId) != null && awaitingNotifications.get(requestId).size() == 0 || // No notifications 
				(awaitingNotifications.get(requestId) != null && awaitingNotifications.get(requestId).size() != 0 && isFirstKeepaliveReceived.containsKey(awaitingNotifications.get(requestId).get(0).requestId)==false)) { // Not asked us yet
			try {
				wait();
			} catch (InterruptedException ie) {
				return null;
			}
		}
		if (awaitingNotifications.get(requestId) == null) {
			return null;
		}
		if (logMINOR) {
			Logger.minor(this, "Getting notification, notification:" + awaitingNotifications.get(requestId).get(0) + ",remaining:" + (awaitingNotifications.get(requestId).size() - 1));
		}
		return awaitingNotifications.get(requestId).remove(0);
	}

	/** Returns the cleaner's delay in ms */
	private int getDelayInMs() {
		return (int) (UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000 * 2.1);
	}

	/**
	 * Deletes a request either because of failing or leaving
	 * 
	 * @param requestId
	 *            - The id of the request
	 * @return Was a request deleted?
	 */
	private synchronized boolean deleteRequest(String requestId) {
		if (logMINOR) {
			Logger.minor(this, "DeleteRequest with requestId:" + requestId);
		}
		if (isKeepaliveReceived.containsKey(requestId) == false) {
			if (logMINOR) {
				Logger.minor(this, "Request already cleaned, doing nothing");
			}			
			return false;
		}
		isKeepaliveReceived.remove(requestId);
		isFirstKeepaliveReceived.remove(requestId);
		// Iterate over all the pushed elements present on the page
		for (BaseUpdateableElement element : new ArrayList<BaseUpdateableElement>(pages.get(requestId))) {
			pages.get(requestId).remove(element);
			// FIXME why can't we just unconditionally remove(requestId) at the end?
			if (pages.get(requestId).size() == 0) {
				pages.remove(requestId);
			}
			String id = element.getUpdaterId(requestId);
			elements.get(id).remove(requestId);
			if (elements.get(id).size() == 0) {
				elements.remove(id);
			}
			element.dispose();
			// Delete all notification originated from the deleted element
			for (String events : awaitingNotifications.keySet()) {
				for (UpdateEvent updateEvent : new ArrayList<UpdateEvent>(awaitingNotifications.get(events))) {
					if (updateEvent.requestId.compareTo(requestId) == 0) {
						awaitingNotifications.get(events).remove(updateEvent);
					}
				}
			}
		}
		awaitingNotifications.remove(requestId);
		return true;
	}

	/** An event that tells the client what and how it should be updated */
	public class UpdateEvent {
		private String	requestId;
		private String	elementId;

		private UpdateEvent(String requestId, String elementId) {
			this.requestId = requestId;
			this.elementId = elementId;
		}

		public String getRequestId() {
			return requestId;
		}

		public String getElementId() {
			return elementId;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj instanceof UpdateEvent) {
				UpdateEvent o = (UpdateEvent) obj;
				if (o.getRequestId().compareTo(requestId) == 0 && o.getElementId().compareTo(elementId) == 0) {
					return true;
				}
			}
			return false;
		}
		
		@Override
		public int hashCode() {
			return requestId.hashCode() + elementId.hashCode();
		}

		@Override
		public String toString() {
			return "UpdateEvent[requestId=" + requestId + ",elementId=" + elementId + "]";
		}
	}

	/** A task for the Cleaner, that periodically checks for failed requests. */
	private class CleanerTimerTask implements Runnable {
		@Override
		public void run() {
			synchronized (PushDataManager.this) {
				if (logMINOR) {
					Logger.minor(this, "Cleaner running:" + isKeepaliveReceived);
				}
				isScheduled = false;
				for (Entry<String, Boolean> entry : new HashMap<String, Boolean>(isKeepaliveReceived).entrySet()) {
					if (entry.getValue() == false) {
						if (logMINOR) {
							Logger.minor(this, "Cleaner cleaned request:" + entry.getKey());
						}
						deleteRequest(entry.getKey());
					} else {
						if (logMINOR) {
							Logger.minor(this, "Cleaner reseted request:" + entry.getKey());
						}
						isKeepaliveReceived.put(entry.getKey(), false);
					}
				}
				if (isKeepaliveReceived.size() != 0) {
					if (logMINOR) {
						Logger.minor(this, "Cleaner is queued(2) time:" + System.currentTimeMillis());
					}
					cleaner.queueTimedJob(cleanerTask, "cleanerTask", getDelayInMs(), false, true);
					isScheduled = true;
				}
			}
		}
	}
}
