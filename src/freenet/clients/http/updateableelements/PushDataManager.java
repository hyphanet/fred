package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.db4o.internal.fieldindex.OrIndexedLeaf;

import freenet.node.Ticker;

/** A manager class that manages all the pushing. All it's public method must be synchronized to maintain consistency. */
public class PushDataManager {

	/** What notifications are waiting for the leader */
	private Map<String, List<UpdateEvent>>				awaitingNotifications	= new HashMap<String, List<UpdateEvent>>();

	/** What elements are on the page */
	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	/** What pages are on the element. It is redundant with the pages map. */
	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	/** Stores whether a keepalive was received for a request since the Cleaner last run */
	private Map<String, Boolean>						isKeepaliveReceived		= new HashMap<String, Boolean>();

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
		System.err.println("Element updated id:"+id);
		boolean needsUpdate = false;
		if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
			for (List<UpdateEvent> notificationList : awaitingNotifications.values()) {
				UpdateEvent updateEvent = new UpdateEvent(reqId, id);
				if (notificationList.contains(updateEvent) == false) {
					notificationList.add(updateEvent);
					if(id.compareTo(TesterElement.getId(reqId, ""+0))==0){
						System.err.println("First element notif added to:"+reqId);
					}
					System.err.println("Notification added");
				}
			}
			needsUpdate = true;
		}
		if (needsUpdate) {
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
		// Add to the pages
		if (pages.containsKey(requestUniqueId) == false) {
			pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
		}
		pages.get(requestUniqueId).add(element);
		// Add to the elements
		if (elements.containsKey(element.getUpdaterId(requestUniqueId)) == false) {
			elements.put(element.getUpdaterId(requestUniqueId), new ArrayList<String>());
		}
		elements.get(element.getUpdaterId(requestUniqueId)).add(requestUniqueId);
		// The request needs to be tracked
		isKeepaliveReceived.put(requestUniqueId, true);
		
		if(awaitingNotifications.containsKey(requestUniqueId)==false){
			awaitingNotifications.put(requestUniqueId, new ArrayList<UpdateEvent>());
		}
		// If the Cleaner isn't running, then we schedule it to clear this request if failing
		if (isScheduled == false) {
			System.err.println("Cleaner is queued(1) time:" + System.currentTimeMillis());
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
		if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
			if (element.getUpdaterId(requestId).compareTo(id) == 0) {
				element.updateState();
				return element;
			}
		}
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
		System.err.println("Failover in, original:"+originalRequestId+" new:"+newRequestId);
		if (awaitingNotifications.containsKey(originalRequestId)) {
			awaitingNotifications.put(newRequestId, awaitingNotifications.remove(originalRequestId));
			System.err.println("copied "+awaitingNotifications.get(newRequestId).size()+" notification:"+awaitingNotifications.get(newRequestId));
			notifyAll();
			return true;
		} else {
			System.err.println("Does not contains key");
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
		try {
			return deleteRequest(requestId);
		} finally {
			System.err.println("Remaining pages num:" + pages.size());
		}
	}

	/**
	 * A keepalive received.
	 * 
	 * @param requestId
	 *            - The id of the request that sent the keepalive
	 * @return Was it successful?
	 */
	public synchronized boolean keepAliveReceived(String requestId) {
		// If the request is already deleted, then fail
		if (isKeepaliveReceived.containsKey(requestId) == false) {
			return false;
		}
		isKeepaliveReceived.put(requestId, true);
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
		System.err.println("Polling for notification:"+requestId);
		while (awaitingNotifications.get(requestId) != null && awaitingNotifications.get(requestId).size() == 0) {
			try {
				wait();
			} catch (InterruptedException ie) {
				return null;
			}
		}
		if (awaitingNotifications.get(requestId) == null) {
			return null;
		}
		System.err.println("Getting notification, notification:"+awaitingNotifications.get(requestId).get(0)+",remaining:"+(awaitingNotifications.get(requestId).size()-1));
		if(awaitingNotifications.get(requestId).get(0).elementId.compareTo(TesterElement.getId(requestId, ""+0))==0){
			System.err.println("First element notif dispatched to:"+requestId);
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
	private boolean deleteRequest(String requestId) {
		System.err.println("DeleteRequest with requestId:"+requestId);
		if (isKeepaliveReceived.containsKey(requestId) == false) {
			return false;
		}
		isKeepaliveReceived.remove(requestId);
		// Iterate over all the pushed elements present on the page
		for (BaseUpdateableElement element : new ArrayList<BaseUpdateableElement>(pages.get(requestId))) {
			pages.get(requestId).remove(element);
			if (pages.get(requestId).size() == 0) {
				pages.remove(requestId);
			}
			elements.get(element.getUpdaterId(requestId)).remove(requestId);
			if (elements.get(element.getUpdaterId(requestId)).size() == 0) {
				elements.remove(element.getUpdaterId(requestId));
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
			if(obj instanceof UpdateEvent){
				UpdateEvent o=(UpdateEvent)obj;
				if(o.getRequestId().compareTo(requestId)==0 && o.getElementId().compareTo(elementId)==0){
					return true;
				}
			}
			return false;
		}
		
		@Override
		public String toString() {
			return "UpdateEvent[requestId="+requestId+",elementId="+elementId+"]";
		}
	}

	/** A task for the Cleaner, that periodically checks for failed requests. */
	private class CleanerTimerTask implements Runnable {
		public void run() {
			synchronized (PushDataManager.this) {
				System.err.println("Cleaner running:" + isKeepaliveReceived);
				isScheduled = false;
				System.err.println("Cleaner running time:" + System.currentTimeMillis());
				for (Entry<String, Boolean> entry : new HashMap<String, Boolean>(isKeepaliveReceived).entrySet()) {
					if (entry.getValue() == false) {
						System.err.println("Cleaner cleaned request:" + entry.getKey());
						deleteRequest(entry.getKey());
					} else {
						System.err.println("Cleaner reseted request:" + entry.getKey());
						isKeepaliveReceived.put(entry.getKey(), false);
					}
				}
				if (isKeepaliveReceived.size() != 0 && isScheduled == false) {
					System.err.println("Cleaner is queued(2) time:" + System.currentTimeMillis());
					cleaner.queueTimedJob(cleanerTask, "cleanerTask", getDelayInMs(), false, true);
					isScheduled = true;
				}
			}
		}
	}
}
