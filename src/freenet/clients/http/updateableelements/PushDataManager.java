package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import freenet.node.Ticker;

public class PushDataManager {

	private Map<String, List<UpdateEvent>>				awaitingNotifications	= new HashMap<String, List<UpdateEvent>>();

	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	private Map<String, Boolean>						isKeepaliveReceived		= new HashMap<String, Boolean>();

	private final Lock									elementLock				= new ReentrantLock();

	private Ticker										cleaner;

	private CleanerTimerTask							cleanerTask				= new CleanerTimerTask();

	public PushDataManager(Ticker ticker) {
		cleaner = ticker;
	}

	public void updateElement(String id) {
		boolean needsUpdate = false;
		synchronized (awaitingNotifications) {
			if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
				for (List<UpdateEvent> notificationList : awaitingNotifications.values()) {
					notificationList.add(new UpdateEvent(reqId, id));
				}
				needsUpdate = true;
			}
			if (needsUpdate) {
				awaitingNotifications.notifyAll();
			}
		}
	}

	public void elementRendered(String requestUniqueId, BaseUpdateableElement element) {
		elementLock.lock();
		try {
			if (pages.containsKey(requestUniqueId) == false) {
				pages.put(requestUniqueId, new ArrayList<BaseUpdateableElement>());
			}
			pages.get(requestUniqueId).add(element);
			if (elements.containsKey(element.getUpdaterId(requestUniqueId)) == false) {
				elements.put(element.getUpdaterId(requestUniqueId), new ArrayList<String>());
			}
			elements.get(element.getUpdaterId(requestUniqueId)).add(requestUniqueId);
			isKeepaliveReceived.put(requestUniqueId, true);
			cleaner.queueTimedJob(cleanerTask, getDelayInMs());
		} finally {
			elementLock.unlock();
		}
	}

	public BaseUpdateableElement getRenderedElement(String requestId, String id) {
		elementLock.lock();
		try {
			if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
				if (element.getUpdaterId(requestId).compareTo(id) == 0) {
					element.updateState();
					return element;
				}
			}
			return null;
		} finally {
			elementLock.unlock();
		}
	}

	public boolean failover(String originalRequestId, String newRequestId) {
		synchronized (awaitingNotifications) {
			if (awaitingNotifications.containsKey(originalRequestId)) {
				awaitingNotifications.put(newRequestId, awaitingNotifications.remove(originalRequestId));
				awaitingNotifications.notifyAll();
				return true;
			} else {
				return false;
			}
		}
	}

	public boolean keepAliveReceived(String requestId) {
		elementLock.lock();
		try {
			if (isKeepaliveReceived.containsKey(requestId) == false) {
				return false;
			}
			isKeepaliveReceived.put(requestId, true);
			return true;
		} finally {
			elementLock.unlock();
		}
	}

	public UpdateEvent getNextNotification(String requestId) {
		synchronized (awaitingNotifications) {
			if (awaitingNotifications.containsKey(requestId) == false) {
				elementLock.lock();
				try {
					if (pages.containsKey(requestId) == false) {
						// return null;
					}
				} finally {
					elementLock.unlock();
				}
				awaitingNotifications.put(requestId, new ArrayList<UpdateEvent>());
			}
			while (awaitingNotifications.get(requestId).size() == 0) {
				try {
					awaitingNotifications.wait();
				} catch (InterruptedException ie) {
					return null;
				}
			}
			return awaitingNotifications.get(requestId).remove(0);
		}
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
			elementLock.lock();
			try {
				for (Entry<String, Boolean> entry : new HashMap<String, Boolean>(isKeepaliveReceived).entrySet()) {
					if (entry.getValue() == false) {
						isKeepaliveReceived.remove(entry.getKey());
						for (BaseUpdateableElement element : new ArrayList<BaseUpdateableElement>(pages.get(entry.getKey()))) {
							pages.get(entry.getKey()).remove(element);
							if (pages.get(entry.getKey()).size() == 0) {
								pages.remove(entry.getKey());
							}
							elements.remove(element.getUpdaterId(entry.getKey()));
							element.dispose();
						}
						synchronized (awaitingNotifications) {
							awaitingNotifications.remove(entry.getKey());
						}
					} else {
						isKeepaliveReceived.put(entry.getKey(), false);
					}
				}
			} finally {
				elementLock.unlock();
				if(isKeepaliveReceived.size()!=0){
					cleaner.queueTimedJob(cleanerTask, getDelayInMs());
				}
			}
		}
	}
}
