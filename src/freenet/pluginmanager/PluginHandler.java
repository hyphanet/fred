package freenet.pluginmanager;

import freenet.support.Logger;
import freenet.support.OOMHandler;

/**
 * Methods to handle a specific plugin (= set it up and start it)
 * 
 * @author cyberdo
 */
public class PluginHandler {

	/**
	 * Will get all needed info from the plugin, put it into the Wrapper. Then
	 * the Pluginstarter will be greated, and the plugin fedto it, starting the
	 * plugin.
	 * 
	 * the pluginInfoWrapper will then be returned
	 * 
	 * @param plug
	 */
	public static void startPlugin(PluginManager pm, PluginInfoWrapper pi) {
		final PluginStarter ps = new PluginStarter(pm, pi);

		FredPlugin plug = pi.getPlugin();
		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = plug.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
		// We must start the plugin *after startup has finished*
		Runnable job;
		if(!pi.isThreadlessPlugin()) {
			final Thread t = new Thread(ps);
			t.setDaemon(true);
			pi.setThread(t);
			job = new Runnable() {
				public void run() {
					t.start();
				}
			};
			pm.getTicker().queueTimedJob(job, 0);
		} else {
			// Avoid NPEs: let it init, then register it.
			plug.runPlugin(pi.getPluginRespirator());
			pm.register(pi);
		}
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}
	}
	
	private static class PluginStarter implements Runnable {
		private PluginManager pm = null;
		final PluginInfoWrapper pi;
		
		public PluginStarter(PluginManager pm, PluginInfoWrapper pi) {
			this.pm = pm;
			this.pi = pi;
		}
		
		public void run() {
				try {
					pm.register(pi);
					pi.getPlugin().runPlugin(pi.getPluginRespirator());
				} catch (OutOfMemoryError e) {
					OOMHandler.handleOOM(e);
				} catch (Throwable t) {
					Logger.normal(this, "Caught Throwable while running plugin: "+t, t);
					System.err.println("Caught Throwable while running plugin: "+t);
					t.printStackTrace();
				}
				pi.unregister(pm, false); // If not already unregistered
				pm.removePlugin(pi);
		}
		
	}
}
