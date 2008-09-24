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
	public static PluginInfoWrapper startPlugin(PluginManager pm, String filename, FredPlugin plug, PluginRespirator pr) {
		final PluginInfoWrapper pi = new PluginInfoWrapper(pr, plug, filename);
		final PluginStarter ps = new PluginStarter(pr, pi);
		ps.setPlugin(pm, plug);
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
		} else {
			job = ps;
		}
		// Run immediately after startup
		pm.getTicker().queueTimedJob(job, 0);
		return pi;
	}
	
	private static class PluginStarter implements Runnable {
		private FredPlugin plugin = null;
		private PluginRespirator pr;
		private PluginManager pm = null;
		final PluginInfoWrapper pi;
		
		public PluginStarter(PluginRespirator pr, PluginInfoWrapper pi) {
			this.pr = pr;
			this.pi = pi;
		}
		
		public void setPlugin(PluginManager pm, FredPlugin plugin) {
			this.plugin = plugin;
			this.pm = pm;
		}
		
		public void run() {
			boolean threadless = plugin instanceof FredPluginThreadless;
				try {
					if(!threadless) // Have to do it now because threaded
						pm.register(plugin, pi);
					((FredPlugin)plugin).runPlugin(pr);
					if(threadless) // Don't want it to receive callbacks until after it has the PluginRespirator, else get NPEs
						pm.register(plugin, pi);
				} catch (OutOfMemoryError e) {
					OOMHandler.handleOOM(e);
				} catch (Throwable t) {
					Logger.normal(this, "Caught Throwable while running plugin: "+t, t);
					System.err.println("Caught Throwable while running plugin: "+t);
					t.printStackTrace();
				}
				if(!threadless) {
					pi.unregister(pm); // If not already unregistered
					pm.removePlugin(pi);
				}
		}
		
	}
}
