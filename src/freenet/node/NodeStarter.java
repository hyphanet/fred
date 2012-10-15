/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;
import java.io.IOException;

import org.tanukisoftware.wrapper.WrapperListener;
import org.tanukisoftware.wrapper.WrapperManager;

import freenet.config.FreenetFilePersistentConfig;
import freenet.config.InvalidConfigValueException;
import freenet.config.PersistentConfig;
import freenet.config.SubConfig;
import freenet.crypt.DiffieHellman;
import freenet.crypt.RandomSource;
import freenet.crypt.SSL;
import freenet.crypt.Yarrow;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.io.NativeThread;

/**
 *  @author nextgens
 *
 *  A class to tie the wrapper and the node (needed for self-restarting support)
 */
public class NodeStarter implements WrapperListener {

	private Node node;
	private static LoggingConfigHandler logConfigHandler;
	/** Freenet will not function at all without at least this build of freenet-ext.jar.
	 * This will be included in the jar manifest file so we can check it when we download new builds. */
	public final static int REQUIRED_EXT_BUILD_NUMBER = -1;//@min.ext.version@;
	/** Freenet will function best with this build of freenet-ext.jar.
	 * It may be required in the near future. The node will try to download it.
	 * The node will not update to a later ext version than this, because that might be incompatible. */
	public final static int RECOMMENDED_EXT_BUILD_NUMBER = -1;//@max.ext.version@;
	/*
	(File.separatorChar == '\\') &&
	(System.getProperty("os.arch").toLowerCase().matches("(i?[x0-9]86_64|amd64)")) ? 6 : 2;
	 */
	public static final int extBuildNumber;
	public static final String extRevisionNumber;

	static {
		extBuildNumber = ExtVersion.extBuildNumber();
		extRevisionNumber = ExtVersion.extRevisionNumber();
	}

	private FreenetFilePersistentConfig cfg;

	// experimental osgi support
	private static NodeStarter nodestarter_osgi = null;

	/*---------------------------------------------------------------
	 * Constructors
	 *-------------------------------------------------------------*/
	private NodeStarter() {
	}

	public NodeStarter get() {
		return this;
	}

	/*---------------------------------------------------------------
	 * WrapperListener Methods
	 *-------------------------------------------------------------*/
	/**
	 * The start method is called when the WrapperManager is signaled by the
	 *	native wrapper code that it can start its application.  This
	 *	method call is expected to return, so a new thread should be launched
	 *	if necessary.
	 *
	 * @param args List of arguments used to initialize the application.
	 *
	 * @return Any error code if the application should exit on completion
	 *         of the start method.  If there were no problems then this
	 *         method should return null.
	 */
	@Override
	public Integer start(String[] args) {
		if(args.length > 1) {
			System.out.println("Usage: $ java freenet.node.Node <configFile>");
			return Integer.valueOf(-1);
		}

		String builtWithMessage = "freenet.jar built with freenet-ext.jar Build #" + ExtVersion.buildNumber + " r" + ExtVersion.cvsRevision+" running with ext build "+extBuildNumber+" r" + extRevisionNumber;
		Logger.normal(this, builtWithMessage);
		System.out.println(builtWithMessage);

		File configFilename;
		if(args.length == 0) {
			System.out.println("Using default config filename freenet.ini");
			configFilename = new File("freenet.ini");
		} else
			configFilename = new File(args[0]);

		// set Java's DNS cache not to cache forever, since many people
		// use dyndns hostnames
		java.security.Security.setProperty("networkaddress.cache.ttl", "0");
		java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");

		try {
			System.out.println("Creating config from "+configFilename);
			cfg = FreenetFilePersistentConfig.constructFreenetFilePersistentConfig(configFilename);
		} catch(IOException e) {
			System.out.println("Error : " + e);
			e.printStackTrace();
			return Integer.valueOf(-1);
		}

		// First, set up logging. It is global, and may be shared between several nodes.
		SubConfig loggingConfig = new SubConfig("logger", cfg);

		PooledExecutor executor = new PooledExecutor();

		try {
			System.out.println("Creating logger...");
			logConfigHandler = new LoggingConfigHandler(loggingConfig, executor);
		} catch(InvalidConfigValueException e) {
			System.err.println("Error: could not set up logging: " + e.getMessage());
			e.printStackTrace();
			return Integer.valueOf(-2);
		}

		System.out.println("Starting executor...");
		executor.start();

		// Prevent timeouts for a while. The DiffieHellman init for example could take some time on a very slow system.
		WrapperManager.signalStarting(500000);

		// Thread to keep the node up.
		// JVM deadlocks losing a lock when two threads of different types (daemon|app)
		// are contended for the same lock. So make USM daemon, and use useless to keep the JVM
		// up.
		// http://forum.java.sun.com/thread.jspa?threadID=343023&messageID=2942637 - last message
		Runnable useless =
			new Runnable() {

				@Override
				public void run() {
					while(true) {
						try {
							Thread.sleep(60 * 60 * 1000);
						} catch(InterruptedException e) {
							// Ignore
						} catch(Throwable t) {
							try {
								Logger.error(this, "Caught " + t, t);
							} catch(Throwable t1) {
								// Ignore
							}
						}
					}
				}
			};
		NativeThread plug = new NativeThread(useless, "Plug", NativeThread.MAX_PRIORITY, false);
		// Not daemon, but doesn't do anything.
		// Keeps the JVM alive.
		// DO NOT do anything in the plug thread, if you do you risk the EvilJVMBug.
		plug.setDaemon(false);
		plug.start();

		// Initialize SSL
		SubConfig sslConfig = new SubConfig("ssl", cfg);
		SSL.init(sslConfig);

		try {
			node = new Node(cfg, null, null, logConfigHandler, this, executor);
			node.start(false);
			System.out.println("Node initialization completed.");
		} catch(NodeInitException e) {
			System.err.println("Failed to load node: " + e.exitCode + " : " + e.getMessage());
			e.printStackTrace();
			System.exit(e.exitCode);
		}

		return null;
	}

	/**
	 * Called when the application is shutting down.  The Wrapper assumes that
	 *  this method will return fairly quickly.  If the shutdown code code
	 *  could potentially take a long time, then WrapperManager.signalStopping()
	 *  should be called to extend the timeout period.  If for some reason,
	 *  the stop method can not return, then it must call
	 *  WrapperManager.stopped() to avoid warning messages from the Wrapper.
	 *
	 * @param exitCode The suggested exit code that will be returned to the OS
	 *                 when the JVM exits.
	 *
	 * @return The exit code to actually return to the OS.  In most cases, this
	 *         should just be the value of exitCode, however the user code has
	 *         the option of changing the exit code if there are any problems
	 *         during shutdown.
	 */
	@Override
	public int stop(int exitCode) {
		System.err.println("Shutting down with exit code " + exitCode);
		node.park();
		// see #354
		WrapperManager.signalStopping(120000);

		return exitCode;
	}

	public void restart() {
		WrapperManager.restart();
	}

	/**
	 * Called whenever the native wrapper code traps a system control signal
	 *  against the Java process.  It is up to the callback to take any actions
	 *  necessary.  Possible values are: WrapperManager.WRAPPER_CTRL_C_EVENT,
	 *    WRAPPER_CTRL_CLOSE_EVENT, WRAPPER_CTRL_LOGOFF_EVENT, or
	 *    WRAPPER_CTRL_SHUTDOWN_EVENT
	 *
	 * @param event The system control signal.
	 */
	@Override
	public void controlEvent(int event) {
		if(WrapperManager.isControlledByNativeWrapper()) {
			// The Wrapper will take care of this event
		} else
			// We are not being controlled by the Wrapper, so
			//  handle the event ourselves.
			if((event == WrapperManager.WRAPPER_CTRL_C_EVENT) ||
				(event == WrapperManager.WRAPPER_CTRL_CLOSE_EVENT) ||
				(event == WrapperManager.WRAPPER_CTRL_SHUTDOWN_EVENT))
				WrapperManager.stop(0);
	}

	/*---------------------------------------------------------------
	 * Main Method
	 *-------------------------------------------------------------*/
	public static void main(String[] args) {
		// Start the application.  If the JVM was launched from the native
		//  Wrapper then the application will wait for the native Wrapper to
		//  call the application's start method.  Otherwise the start method
		//  will be called immediately.
		WrapperManager.start(new NodeStarter(), args);
	}

	static SemiOrderedShutdownHook shutdownHook;

	/**
	 * VM-specific init.
	 * Not Node-specific; many nodes may be created later.
	 * @param testName The name of the test instance.
	 */
	public static RandomSource globalTestInit(String testName, boolean enablePlug, LogLevel logThreshold, String details, boolean noDNS) throws InvalidThresholdException {

		File dir = new File(testName);
		if((!dir.mkdir()) && ((!dir.exists()) || (!dir.isDirectory()))) {
			System.err.println("Cannot create directory for test");
			System.exit(NodeInitException.EXIT_TEST_ERROR);
		}

		Logger.setupStdoutLogging(logThreshold, details);

		// set Java's DNS cache not to cache forever, since many people
		// use dyndns hostnames
		java.security.Security.setProperty("networkaddress.cache.ttl", "0");
		java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");

		// Setup RNG
		RandomSource random = new Yarrow();

		DiffieHellman.init(random);

		if(enablePlug) {

			// Thread to keep the node up.
			// JVM deadlocks losing a lock when two threads of different types (daemon|app)
			// are contended for the same lock. So make USM daemon, and use useless to keep the JVM
			// up.
			// http://forum.java.sun.com/thread.jspa?threadID=343023&messageID=2942637 - last message
			Runnable useless =
				new Runnable() {

					@Override
					public void run() {
						while(true) {
							try {
								Thread.sleep(60 * 60 * 1000);
							} catch(InterruptedException e) {
								// Ignore
							} catch(Throwable t) {
								try {
									Logger.error(this, "Caught " + t, t);
								} catch(Throwable t1) {
									// Ignore
								}
							}
						}
					}
				};
			Thread plug = new Thread(useless, "Plug");
			// Not daemon, but doesn't do anything.
			// Keeps the JVM alive.
			// DO NOT do anything in the plug thread, if you do you risk the EvilJVMBug.
			plug.setDaemon(false);
			plug.start();
		}

		DNSRequester.DISABLE = noDNS;

		return random;
	}

	public static Node createTestNode(int port, int opennetPort, String testName, boolean disableProbabilisticHTLs,
	                                  short maxHTL, int dropProb, RandomSource random,
	                                  Executor executor, int threadLimit, long storeSize, boolean ramStore,
	                                  boolean enableSwapping, boolean enableARKs, boolean enableULPRs, boolean enablePerNodeFailureTables,
	                                  boolean enableSwapQueueing, boolean enablePacketCoalescing,
	                                  int outputBandwidthLimit, boolean enableFOAF,
	                                  boolean connectToSeednodes, boolean longPingTimes, boolean useSlashdotCache, String ipAddressOverride) throws NodeInitException {
		return createTestNode(port, opennetPort, testName, disableProbabilisticHTLs, maxHTL, dropProb, random, executor,
		    threadLimit, storeSize, ramStore, enableSwapping, enableARKs, enableULPRs, enablePerNodeFailureTables,
		    enableSwapQueueing, enablePacketCoalescing, outputBandwidthLimit, enableFOAF, connectToSeednodes,
		    longPingTimes, useSlashdotCache, ipAddressOverride, false);
	}

	/**
	 * Create a test node.
	 * @param port The node port number. Each test node must have a different port
	 * number.
	 * @param testName The test name.
	 * @throws NodeInitException If the node cannot start up for some reason, most
	 * likely a config problem.
	 */
	public static Node createTestNode(int port, int opennetPort, String testName, boolean disableProbabilisticHTLs,
		short maxHTL, int dropProb, RandomSource random,
		Executor executor, int threadLimit, long storeSize, boolean ramStore,
		boolean enableSwapping, boolean enableARKs, boolean enableULPRs, boolean enablePerNodeFailureTables,
		boolean enableSwapQueueing, boolean enablePacketCoalescing,
		int outputBandwidthLimit, boolean enableFOAF,
		boolean connectToSeednodes, boolean longPingTimes, boolean useSlashdotCache, String ipAddressOverride, boolean enableFCP) throws NodeInitException {

		File baseDir = new File(testName);
		File portDir = new File(baseDir, Integer.toString(port));
		if((!portDir.mkdir()) && ((!portDir.exists()) || (!portDir.isDirectory()))) {
			System.err.println("Cannot create directory for test");
			System.exit(NodeInitException.EXIT_TEST_ERROR);
		}

		// Set up config for testing
		SimpleFieldSet configFS = new SimpleFieldSet(false); // only happens once in entire simulation
		if(outputBandwidthLimit > 0) {
			configFS.put("node.outputBandwidthLimit", outputBandwidthLimit);
			configFS.put("node.throttleLocalTraffic", true);
		} else {
			// Even with throttleLocalTraffic=false, requests still count in NodeStats.
			// So set outputBandwidthLimit to something insanely high.
			configFS.put("node.outputBandwidthLimit", 16 * 1024 * 1024);
			configFS.put("node.throttleLocalTraffic", false);
		}
		configFS.put("node.useSlashdotCache", useSlashdotCache);
		configFS.put("node.listenPort", port);
		configFS.put("node.disableProbabilisticHTLs", disableProbabilisticHTLs);
		configFS.put("fproxy.enabled", false);
		configFS.put("fcp.enabled", enableFCP);
		configFS.put("fcp.port", 9481);
		configFS.put("fcp.ssl", false);
		configFS.put("console.enabled", false);
		configFS.putSingle("pluginmanager.loadplugin", "");
		configFS.put("node.updater.enabled", false);
		configFS.putSingle("node.install.tempDir", new File(portDir, "temp").toString());
		configFS.putSingle("node.install.storeDir", new File(portDir, "store").toString());
		configFS.put("fcp.persistentDownloadsEnabled", false);
		configFS.putSingle("node.throttleFile", new File(portDir, "throttle.dat").toString());
		configFS.putSingle("node.install.nodeDir", portDir.toString());
		configFS.putSingle("node.install.userDir", portDir.toString());
		configFS.putSingle("node.install.runDir", portDir.toString());
		configFS.putSingle("node.install.cfgDir", portDir.toString());
		configFS.put("node.maxHTL", maxHTL);
		configFS.put("node.testingDropPacketsEvery", dropProb);
		configFS.put("node.alwaysAllowLocalAddresses", true);
		configFS.put("node.includeLocalAddressesInNoderefs", true);
		configFS.put("node.enableARKs", false);
		configFS.put("node.load.threadLimit", threadLimit);
		if(ramStore)
			configFS.putSingle("node.storeType", "ram");
		configFS.put("node.storeSize", storeSize);
		configFS.put("node.disableHangCheckers", true);
		configFS.put("node.enableSwapping", enableSwapping);
		configFS.put("node.enableSwapQueueing", enableSwapQueueing);
		configFS.put("node.enableARKs", enableARKs);
		configFS.put("node.enableULPRDataPropagation", enableULPRs);
		configFS.put("node.enablePerNodeFailureTables", enablePerNodeFailureTables);
		configFS.put("node.enablePacketCoalescing", enablePacketCoalescing);
		configFS.put("node.publishOurPeersLocation", enableFOAF);
		configFS.put("node.routeAccordingToOurPeersLocation", enableFOAF);
		configFS.put("node.opennet.enabled", opennetPort > 0);
		configFS.put("node.opennet.listenPort", opennetPort);
		configFS.put("node.opennet.alwaysAllowLocalAddresses", true);
		configFS.put("node.opennet.oneConnectionPerIP", false);
		configFS.put("node.opennet.assumeNATed", true);
		configFS.put("node.opennet.connectToSeednodes", connectToSeednodes);
		configFS.put("node.encryptTempBuckets", false);
		configFS.put("node.encryptPersistentTempBuckets", false);
		configFS.put("node.enableRoutedPing", true);
		if(ipAddressOverride != null)
			configFS.putSingle("node.ipAddressOverride", ipAddressOverride);
		if(longPingTimes) {
			configFS.put("node.maxPingTime", 100000);
			configFS.put("node.subMaxPingTime", 50000);
		}
		configFS.put("node.respondBandwidth", true);
		configFS.put("node.respondBuild", true);
		configFS.put("node.respondIdentifier", true);
		configFS.put("node.respondLinkLengths", true);
		configFS.put("node.respondLocation", true);
		configFS.put("node.respondStoreSize", true);
		configFS.put("node.respondUptime", true);

		PersistentConfig config = new PersistentConfig(configFS);

		Node node = new Node(config, random, random, null, null, executor);

		//All testing environments connect the nodes as they want, even if the old setup is restored, it is not desired.
		node.peers.removeAllPeers();

		return node;
	}

	// experimental osgi support
	public static void start_osgi(String[] args) {
		nodestarter_osgi = new NodeStarter();
		nodestarter_osgi.start(args);
	}

	// experimental osgi support
	public static void stop_osgi(int exitCode) {
		nodestarter_osgi.stop(exitCode);
		nodestarter_osgi = null;
	}
}
