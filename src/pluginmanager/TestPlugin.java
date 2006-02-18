package pluginmanager;

import java.net.MalformedURLException;
import java.util.Date;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;

public class TestPlugin implements FredPlugin {
	boolean goon = true;
	public void terminate() {
		goon = false;
	}

	public void runPlugin(PluginRespirator pr) {
		int i = (int)System.currentTimeMillis()%1000;
		while(goon) {
			System.err.println("This is a threaded test-plugin (" + 
					i + "). " +
					"Time is now: " + (new Date()));
			FetchResult fr;
			try {
				fr = pr.getHLSimpleClient().fetch(new FreenetURI("freenet:CHK@j-v1zc0cuN3wlaCpxlKd6vT6c1jAnT9KiscVjfzLu54,q9FIlJSh8M1I1ymRBz~A0fsIcGkvUYZahZb5j7uepLA,AAEA--8"));
				System.err.println("  Got data from key, length = " + fr.size() + " Message: "
						+ new String(fr.asByteArray()).trim());
			} catch (Exception e) {
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

}
