package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import freenet.node.Ticker;

public class PushDataManager {

	/** What notifications are waiting for the leader*/
	private Map<String, List<UpdateEvent>>				awaitingNotifications	= new HashMap<String, List<UpdateEvent>>();

	/** What elements are on the page*/
	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	/** What pages are on the element*/
	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	private Map<String, Boolean>						isKeepaliveReceived		= new HashMap<String, Boolean>();

	private Ticker										cleaner;

	private CleanerTimerTask							cleanerTask				= new CleanerTimerTask();

	private boolean										isScheduled				= false;

	public PushDataManager(Ticker ticker) {
		cleaner = ticker;
	}

	public synchronized void updateElement(String id) {
		boolean needsUpdate = false;
		if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
			for (List<UpdateEvent> notificationList : awaitingNotifications.values()) {
				notificationList.add(new UpdateEvent(reqId, id));
			}
			needsUpdate = true;
		}
		if (needsUpdate) {
			notifyAll();
		}
	}

	public synchronized void elementRendered(String requestUniqueId, BaseUpdateableElement element) {
		if (pages.containsKey(requestUniqueId) == false) {
			pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
		}
		pages.get(requestUniqueId).add(element);
		if (elements.containsKey(element.getUpdaterId(requestUniqueId)) == false) {
			elements.put(element.getUpdaterId(requestUniqueId), new ArrayList<String>());
		}
		elements.get(element.getUpdaterId(requestUniqueId)).add(requestUniqueId);
		isKeepaliveReceived.put(requestUniqueId, true);
		if (isScheduled == false) {
			System.err.println("Cleaner is queued(1) time:" + System.currentTimeMillis());
			cleaner.queueTimedJob(cleanerTask, "cleanerTask", getDelayInMs(), false, true);
			isScheduled = true;
		}
	}

	public synchronized BaseUpdateableElement getRenderedElement(String requestId, String id) {
		if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
			if (element.getUpdaterId(requestId).compareTo(id) == 0) {
				element.updateState();
				return element;
			}
		}
		return null;
	}

	public synchronized boolean failover(String originalRequestId, String newRequestId) {
		if (awaitingNotifications.containsKey(originalRequestId)) {
			awaitingNotifications.put(newRequestId, awaitingNotifications.remove(originalRequestId));
			notifyAll();
			return true;
		} else {
			return false;
		}
	}

	public synchronized boolean keepAliveReceived(String requestId) {
		if (isKeepaliveReceived.containsKey(requestId) == false) {
			return false;
		}
		isKeepaliveReceived.put(requestId, true);
		return true;
	}

	public synchronized UpdateEvent getNextNotification(String requestId) {
		if (awaitingNotifications.containsKey(requestId) == false) {
			if (pages.containsKey(requestId) == false) {
				// return null;
			}
			awaitingNotifications.put(requestId, new ArrayList<UpdateEvent>());
		}
		while (awaitingNotifications.get(requestId).size() == 0) {
			try {
				wait();
			} catch (InterruptedException ie) {
				return null;
			}
		}
		return awaitingNotifications.get(requestId).remove(0);
	}

	private int getDelayInMs() {
		return (int) (UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000 * 2.1);
	}

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
	}

	private class CleanerTimerTask implements Runnable {
		public void run() {
			synchronized (PushDataManager.this) {
				isScheduled = false;
				System.err.println("Cleaner running time:" + System.currentTimeMillis());
				for (Entry<String, Boolean> entry : new HashMap<String, Boolean>(isKeepaliveReceived).entrySet()) {
					if (entry.getValue() == false) {
						System.err.println("Cleaner cleaned request:" + entry.getKey());
						isKeepaliveReceived.remove(entry.getKey());
						for (BaseUpdateableElement element : new ArrayList<BaseUpdateableElement>(pages.get(entry.getKey()))) {
							pages.get(entry.getKey()).remove(element);
							if (pages.get(entry.getKey()).size() == 0) {
								pages.remove(entry.getKey());
							}
							elements.remove(element.getUpdaterId(entry.getKey()));
							element.dispose();
							for(String events:awaitingNotifications.keySet()){
								for(UpdateEvent updateEvent:new ArrayList<UpdateEvent>(awaitingNotifications.get(events))){
									if(updateEvent.requestId.compareTo(entry.getKey())==0){
										awaitingNotifications.get(events).remove(updateEvent);
									}
								}
							}
						}
						awaitingNotifications.remove(entry.getKey());
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
