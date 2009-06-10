package freenet.client.connection;

import freenet.client.tools.Base64;
import freenet.client.update.DefaultUpdateManager;
import freenet.client.update.IUpdateManager;

public class SharedConnectionManager implements IConnectionManager, IUpdateManager {

	private LongPollingConnectionManager	longPollingManager	= new LongPollingConnectionManager(this);

	private IUpdateManager					updateManager		= null;

	public SharedConnectionManager(IUpdateManager updateManager) {
		this.updateManager = updateManager;
	}

	@Override
	public void closeConnection() {
		longPollingManager.closeConnection();
	}

	@Override
	public void openConnection() {// TODO
		if (updateManager == null) {
			throw new RuntimeException("You must set the UpdateManager before opening the connection!");
		}
		longPollingManager.openConnection();
	}

	@Override
	public void updated(String message) {
		String requestId = Base64.decode(message.substring(0, message.indexOf(DefaultUpdateManager.SEPARATOR)));
		if (requestId.compareTo(((DefaultUpdateManager) updateManager).requestId) == 0) {
			String msg = message.substring(message.indexOf(DefaultUpdateManager.SEPARATOR) + 1);
			updateManager.updated(msg);
		}
		// TODO Auto-generated method stub

	}

}
