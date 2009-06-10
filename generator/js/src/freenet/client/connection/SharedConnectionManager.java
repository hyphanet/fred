package freenet.client.connection;

import freenet.client.update.IUpdateManager;

public class SharedConnectionManager implements IConnectionManager, IUpdateManager {

	private LongPollingConnectionManager	longPollingManager	= new LongPollingConnectionManager(this);

	private IUpdateManager					updateManager		= null;

	public SharedConnectionManager(IUpdateManager updateManager){
		this.updateManager=updateManager;
	}
	
	@Override
	public void closeConnection() {
		longPollingManager.closeConnection();
	}

	@Override
	public void openConnection() {//TODO
		if (updateManager == null) {
			throw new RuntimeException("You must set the UpdateManager before opening the connection!");
		}
		longPollingManager.openConnection();
	}

	@Override
	public void updated(String message) {
		updateManager.updated(message);
		// TODO Auto-generated method stub

	}

}
 