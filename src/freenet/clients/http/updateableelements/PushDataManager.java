package freenet.clients.http.updateableelements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PushDataManager {

	private List<UpdateEvent>							awaitingNotifications	= new ArrayList<UpdateEvent>();

	private Map<String, List<BaseUpdateableElement>>	pages					= new HashMap<String, List<BaseUpdateableElement>>();

	private Map<String, List<String>>					elements				= new HashMap<String, List<String>>();

	private Map<String, Boolean>						isKeepaliveReceived		= new HashMap<String, Boolean>();

	private final Lock									elementLock				= new ReentrantLock();

	private Timer										cleaner;

	public void updateElement(String id) {
		boolean needsUpdate = false;
		synchronized (awaitingNotifications) {
			if (elements.containsKey(id)) for (String reqId : elements.get(id)) {
				awaitingNotifications.add(new UpdateEvent(reqId, id));
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
			if (elements.containsKey(element.getUpdaterId()) == false) {
				elements.put(element.getUpdaterId(), new ArrayList<String>());
			}
			elements.get(element.getUpdaterId()).add(requestUniqueId);
			isKeepaliveReceived.put(requestUniqueId, true);
			if (cleaner == null) {
				int delayInMs = (int) (UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000 * 2.1);
				cleaner=new Timer(false);
				cleaner.schedule(new CleanerTimerTask(), delayInMs, delayInMs);
			}
		} finally {
			elementLock.unlock();
		}
	}

	public BaseUpdateableElement getRenderedElement(String requestId, String id) {
		elementLock.lock();
		try {
			if (pages.get(requestId) != null) for (BaseUpdateableElement element : pages.get(requestId)) {
				if (element.getUpdaterId().compareTo(id) == 0) {
					element.updateState();
					return element;
				}
			}
			return null;
		} finally {
			elementLock.unlock();
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

	public UpdateEvent getNextNotification() {
		synchronized (awaitingNotifications) {
			while (awaitingNotifications.size() == 0) {
				try {
					awaitingNotifications.wait();
				} catch (InterruptedException ie) {
					return null;
				}
			}
			return awaitingNotifications.remove(0);
		}
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

	private class CleanerTimerTask extends TimerTask {
		@Override
		public void run() {
			System.err.println("Cleaner started");
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
							elements.remove(element.getUpdaterId());
							element.dispose();
						}
						if (isKeepaliveReceived.size() == 0) {
							cleaner.cancel();
							cleaner=null;
						}
						System.err.println("Cleaner has deleted key:" + entry.getKey());
						System.err.println("current status:");
						System.err.println("awaitingNotifications:" + awaitingNotifications);
						System.err.println("pages:" + pages);
						System.err.println("elements:" + elements);
						System.err.println("isKeepaliveReceived:" + isKeepaliveReceived);
					} else {
						isKeepaliveReceived.put(entry.getKey(), false);
						System.err.println("Cleaner has reseted key:" + entry.getKey());
					}
				}
			} finally {
				elementLock.unlock();
			}
		}
	}
}
