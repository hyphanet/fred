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

public class SharedConnectionManager implements IConnectionManager, IUpdateManager {

	private static final int				sharedConnectionKeepaliveIntervalInMs	= 1000;

	private static final String				LEADER_KEEPALIVE						= "LeaderKeepalive";

	private static final String				LEADER_NAME								= "LeaderName";

	private static final String				MESSAGE_COUNTER							= "MessageCounter";

	private static final String				MESSAGE_PREFIX							= "Message";

	private static final int				MAX_MESSAGES							= 10;

	private long							messageCounter;

	private LongPollingConnectionManager	longPollingManager						= new LongPollingConnectionManager(this);

	private IUpdateManager					updateManager							= null;
	
	private boolean isLeading=false;

	private Timer							followerTakeOverTimer;

	private Timer							followerNotifierTimer;

	public SharedConnectionManager(IUpdateManager updateManager) {
		this.updateManager = updateManager;
		followerTakeOverTimer = new Timer() {
			@Override
			public void run() {
				if ((Long.parseLong(Cookies.getCookie(LEADER_KEEPALIVE)) + sharedConnectionKeepaliveIntervalInMs * 3) < getTime()) {
					FreenetJs.log("Getting leadership lastKeepalive:" + Cookies.getCookie(LEADER_KEEPALIVE));
					followerTakeOverTimer.cancel();
					followerNotifierTimer.cancel();
					String originalLeader = Cookies.getCookie(LEADER_NAME);
					if (originalLeader != null) {
						FreenetRequest.sendRequest(UpdaterConstants.failoverPath, new QueryParameter[] { new QueryParameter("requestId", FreenetJs.requestId),
								new QueryParameter("originalRequestId", originalLeader) });
					}
					startLeading();
				}
			}
		};
		followerNotifierTimer = new Timer() {

			@Override
			public void run() {
				processMessages();
			}
		};
		if (Cookies.getCookie(MESSAGE_COUNTER) != null) {
			messageCounter = Long.parseLong(Cookies.getCookie(MESSAGE_COUNTER));
		} else {
			messageCounter = 0;
		}
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
		if (Cookies.getCookie(LEADER_NAME) == null ||Cookies.getCookie(LEADER_NAME).trim().compareTo("")==0 || (Cookies.getCookie(LEADER_KEEPALIVE) == null || (Long.parseLong(Cookies.getCookie(LEADER_KEEPALIVE)) + sharedConnectionKeepaliveIntervalInMs * 3) < getTime())) {
			startLeading();
		} else {
			followerTakeOverTimer.scheduleRepeating(sharedConnectionKeepaliveIntervalInMs * 3);
			followerNotifierTimer.scheduleRepeating(100);
		}
	}

	private void startLeading() {
		FreenetJs.log("Starting leading");
		isLeading=true;
		Cookies.setCookie(LEADER_NAME, FreenetJs.requestId, null, null, "/", false);
		Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
		processMessages();
		longPollingManager.openConnection();
		new Timer() {
			public void run() {
				Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
				FreenetJs.log("Setting leader keepalive:" + Cookies.getCookie(LEADER_KEEPALIVE));
			};
		}.scheduleRepeating(sharedConnectionKeepaliveIntervalInMs);
	}
	
	private void stopLeading(){
		FreenetJs.log("Stopping leading");
		Cookies.setCookie(LEADER_NAME, "", null, null, "/", false);
	}

	@Override
	public void updated(String message) {
		String requestId = Base64.decode(message.substring(0, message.indexOf(DefaultUpdateManager.SEPARATOR)));
		String msg = message.substring(message.indexOf(DefaultUpdateManager.SEPARATOR) + 1);
		FreenetJs.log("SharedConnectionManagaer updated:requestId:" + requestId);
		if (requestId.compareTo(FreenetJs.requestId) == 0) {
			updateManager.updated(msg);
			FreenetJs.log("Updating");
		} else {
			FreenetJs.log("Setting cookie: name:"+MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES)+" value:"+message);
			Cookies.setCookie(MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES), message, null, null, "/", false);
			++messageCounter;
			FreenetJs.log("Setting message counter:"+messageCounter);
			Cookies.setCookie(MESSAGE_COUNTER, "" + messageCounter, null, null, "/", false);
		}
		Cookies.setCookie(LEADER_KEEPALIVE, "" + getTime(), null, null, "/", false);
	}

	private void processMessages() {
		FreenetJs.log("Processing messages");
		if (Cookies.getCookie(MESSAGE_COUNTER) == null) {
			return;
		}
		FreenetJs.log("Message counter set, value:"+Cookies.getCookie(MESSAGE_COUNTER)+" internal message counter:"+messageCounter);
		while (messageCounter < Long.parseLong(Cookies.getCookie(MESSAGE_COUNTER))) {
			FreenetJs.log("Inside the loop: internal counter:"+messageCounter+" cookie counter:"+Cookies.getCookie(MESSAGE_COUNTER));
			String message = Cookies.getCookie(MESSAGE_PREFIX + (messageCounter % MAX_MESSAGES));
			FreenetJs.log("Got messsage:"+message);
			String requestId = Base64.decode(message.substring(0, message.indexOf(DefaultUpdateManager.SEPARATOR)));
			FreenetJs.log("Got requestId:"+requestId);
			String msg = message.substring(message.indexOf(DefaultUpdateManager.SEPARATOR) + 1);
			FreenetJs.log("Processing message:requestId:"+requestId+" msg:"+msg);
			if (requestId.compareTo(FreenetJs.requestId) == 0) {
				updateManager.updated(msg);
			}
			messageCounter++;
		}
		FreenetJs.log("Finished processing messages");
		}
	
	private long getTime(){
		return System.currentTimeMillis();
	}

}
