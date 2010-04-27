package freenet.client.connection;

import com.google.gwt.user.client.Cookies;
import com.google.gwt.user.client.Timer;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;
import freenet.client.tools.Base64;
import freenet.client.tools.FreenetRequest;
import freenet.client.tools.QueryParameter;
import freenet.client.update.DefaultUpdateManager;
import freenet.client.update.IUpdateManager;

/** This ConnectionManager manages the notifications in a shared environment. It makes one leader, and it will send the notifications to others via cookies */
public class SharedConnectionManager implements IConnectionManager, IUpdateManager {

	/** The keepalive cookie should be updated in this frequency */
	private static final int				sharedConnectionKeepaliveIntervalInMs	= 1000;

	/** The name of the keepalive cookie */
	private static final String				LEADER_KEEPALIVE						= "LeaderKeepalive";

	/** The name of the leader cookie */
	private static final String				LEADER_NAME								= "LeaderName";

	/** The name of the message counter cookie */
	private static final String				MESSAGE_COUNTER							= "MessageCounter";

	/** The prefix of the massage cookie's name */
	private static final String				MESSAGE_PREFIX							= "Message";

	/** Maximum number of messages cookies. It needs to be maximalized, because browsers handle just a limit number of cookies */
	private static final int				MAX_MESSAGES							= 10;

	/** The internal message counter */
	private long							messageCounter;

	/** This ConnectionManager manages the actual connection to the server if this is the leader */
	private LongPollingConnectionManager	longPollingManager						= new LongPollingConnectionManager(this);

	/** If this request received a notification, this UpdateManager will be notified */
	private IUpdateManager					updateManager							= null;

	/** The timer that periodically checks if the leader is failing */
	private Timer							followerTakeOverTimer;

	/** The timer that checks for received messages via cookies */
	private Timer							followerNotifierTimer;

	public SharedConnectionManager(IUpdateManager updateManager) {
		this.updateManager = updateManager;

		followerTakeOverTimer = new Timer() {
			@Override
			public void run() {
				// Checks if the leader keepalive cookie was updated not long ago
				if ((Long.parseLong(Cookies.getCookie(LEADER_KEEPALIVE)) + sharedConnectionKeepaliveIntervalInMs * 3) < getTime()) {
					// If it isn't updated for a while, then getting the leadership
					FreenetJs.log("Getting leadership lastKeepalive:" + Cookies.getCookie(LEADER_KEEPALIVE));
					// Cancells the follower timers
					followerTakeOverTimer.cancel();
					followerNotifierTimer.cancel();
					// The old leader's name
					String originalLeader = Cookies.getCookie(LEADER_NAME);
					if (originalLeader != null) {
						// If there was an old leader, then notifies the server about the takeover
						FreenetRequest.sendRequest(UpdaterConstants.failoverPath, new QueryParameter[] { new QueryParameter("requestId", FreenetJs.requestId),
								new QueryParameter("originalRequestId", originalLeader) });
					}
					// Starts leading
					startLeading();
				}
			}
		};
		followerNotifierTimer = new Timer() {

			@Override
			public void run() {
				// Process cookie messages every now and then
				processMessages();
			}
		};
		// Sets the message counter
		if (Cookies.getCookie(MESSAGE_COUNTER) != null) {
			messageCounter = Long.parseLong(Cookies.getCookie(MESSAGE_COUNTER));
		} else {
			messageCounter = 0;
		}
		FreenetJs.log("messageCounter initial value "+messageCounter);
	}

	@Override
	public void closeConnection() {
		stopLeading();
		longPollingManager.closeConnection();
	}

	@Override
	public void openConnection() {
		if (updateManager == null) {
			throw new RuntimeException("You must set the UpdateManager before opening the connection!");
		}
		// If there is no leader yet or the keepalive was not updated for a while, then it will take the lead
		if (Cookies.getCookie(LEADER_NAME) == null || Cookies.getCookie(LEADER_NAME).trim().compareTo("") == 0 || (Cookies.getCookie(LEADER_KEEPALIVE) == null || (Long.parseLong(Cookies.getCookie(LEADER_KEEPALIVE)) + sharedConnectionKeepaliveIntervalInMs * 3) < getTime())) {
			startLeading();
		} else {
			// If there is a leader, then it starts the follower timers
			followerTakeOverTimer.scheduleRepeating(sharedConnectionKeepaliveIntervalInMs * 3);
			followerNotifierTimer.scheduleRepeating(100);
		}
	}

	/** Starts leading */
	private void startLeading() {
		FreenetJs.log("Starting leading");
		// Sets the cookies for other tabs
		Cookies.setCookie(LEADER_NAME, FreenetJs.requestId, null, null, "/", false);
		Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
		// Process messages, so there are no messages unprocessed
		processMessages();
		// Opens a connection to the server
		longPollingManager.openConnection();
		// Starts a timer to update the leader keepalive periodically
		new Timer() {
			public void run() {
				Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
				FreenetJs.log("Setting leader keepalive:" + Cookies.getCookie(LEADER_KEEPALIVE));
			};
		}.scheduleRepeating(sharedConnectionKeepaliveIntervalInMs);
	}

	/** Stops leading */
	private void stopLeading() {
		FreenetJs.log("Stopping leading");
		// It just sets the leader name to null, so another tab can take leadership
		Cookies.setCookie(LEADER_NAME, "", null, null, "/", false);
	}

	@Override
	public void updated(String message) {
		// The requestId that is notified
		int idx = message.indexOf(UpdaterConstants.SEPARATOR);
		String requestId = Base64.decode(message.substring(0, idx));
		// The message
		String msg = message.substring(idx + 1);
		FreenetJs.log("SharedConnectionManagaer updated:requestId:" + requestId);
		// Checks if this tab is the destination of the message
		if (requestId.compareTo(FreenetJs.requestId) == 0) {
			// If it is, then call the UpdateManager
			updateManager.updated(msg);
			FreenetJs.log("Updating");
		} else {
			// If not, then set a message and increase the counter, so other tabs will read it
			FreenetJs.log("Setting cookie: name:" + MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES) + " value:" + message);
			Cookies.setCookie(MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES), message, null, null, "/", false);
			++messageCounter;
			FreenetJs.log("Setting message counter:" + messageCounter);
			Cookies.setCookie(MESSAGE_COUNTER, "" + messageCounter, null, null, "/", false);
		}
		// Sets the leader keepalive. It may not be needed
		Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
	}

	/** Processes the messages in the cookies */
	private void processMessages() {
		FreenetJs.log("Processing messages");
		if (Cookies.getCookie(MESSAGE_COUNTER) == null) {
			// If there is no message counter set, then there are no messages
			return;
		}
		FreenetJs.log("Message counter set, value:" + Cookies.getCookie(MESSAGE_COUNTER) + " internal message counter:" + messageCounter);
		// Cycle until all messages are processed
		while (messageCounter < Long.parseLong(Cookies.getCookie(MESSAGE_COUNTER))) {
			FreenetJs.log("Inside the loop: internal counter:" + messageCounter + " cookie counter:" + Cookies.getCookie(MESSAGE_COUNTER));
			String message = Cookies.getCookie(MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES));
			FreenetJs.log("Got messsage:" + message);
			String requestId = Base64.decode(message.substring(0, message.indexOf(UpdaterConstants.SEPARATOR)));
			FreenetJs.log("Got requestId:" + requestId);
			String msg = message.substring(message.indexOf(UpdaterConstants.SEPARATOR) + 1);
			FreenetJs.log("Processing message:requestId:" + requestId + " msg:" + msg);
			if (requestId.compareTo(FreenetJs.requestId) == 0) {
				// If a message is found destined to this request, then call the UpdateManager
				updateManager.updated(msg);
			}
			messageCounter++;
		}
		FreenetJs.log("Finished processing messages");
	}

	/**
	 * Returns the time. It may be needed to provide smaller numbers
	 * 
	 * @return The time
	 */
	private long getTime() {
		return System.currentTimeMillis();
	}

}
