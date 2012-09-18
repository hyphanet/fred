/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import freenet.config.SubConfig;
import freenet.crypt.DummyRandomSource;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.probe.Error;
import freenet.node.probe.Listener;
import freenet.node.probe.Probe;
import freenet.node.probe.Type;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.PooledExecutor;
import freenet.support.io.FileUtil;

import java.io.File;
import java.text.NumberFormat;

/**
 * Create a mesh of nodes and let them sort out their locations.
 *
 * Then present a user interface to run different types of probes from random nodes.
 */
public class RealNodeProbeTest extends RealNodeTest {

	static final int NUMBER_OF_NODES = 100;
	static final int DEGREE = 5;
	static final short MAX_HTL = (short) 5;
	static final boolean START_WITH_IDEAL_LOCATIONS = true;
	static final boolean FORCE_NEIGHBOUR_CONNECTIONS = true;
	static final boolean ENABLE_SWAPPING = false;
	static final boolean ENABLE_SWAP_QUEUEING = false;
	static final boolean ENABLE_FOAF = true;

	public static int DARKNET_PORT_BASE = RealNodeRoutingTest.DARKNET_PORT_END;
	public static final int DARKNET_PORT_END = DARKNET_PORT_BASE + NUMBER_OF_NODES;

	public static void main(String[] args) throws Exception {
		System.out.println("Probe test using real nodes:");
		System.out.println();
		String dir = "realNodeProbeTest";
		File wd = new File(dir);
		if(!FileUtil.removeAll(wd)) {
			System.err.println("Mass delete failed, test may not be accurate.");
			System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
		}
		if (!wd.mkdir()) {
			System.err.println("Unabled to create test directory \"" + dir + "\".");
			return;
		}
		NodeStarter.globalTestInit(dir, false, LogLevel.ERROR, "", true);
		// Make the network reproducible so we can easily compare different routing options by specifying a seed.
		DummyRandomSource random = new DummyRandomSource(3142);
		Node[] nodes = new Node[NUMBER_OF_NODES];
		Logger.normal(RealNodeProbeTest.class, "Creating nodes...");
		Executor executor = new PooledExecutor();
		for(int i = 0; i < NUMBER_OF_NODES; i++) {
			System.err.println("Creating node " + i);
			nodes[i] = NodeStarter.createTestNode(DARKNET_PORT_BASE + i, 0, dir, true, MAX_HTL, 0 /* no dropped packets */, random, executor, 500 * NUMBER_OF_NODES, 65536, true, ENABLE_SWAPPING, false, false, false, ENABLE_SWAP_QUEUEING, true, 0, ENABLE_FOAF, false, true, false, null, i == 0);
			Logger.normal(RealNodeProbeTest.class, "Created node " + i);
		}
		Logger.normal(RealNodeProbeTest.class, "Created " + NUMBER_OF_NODES + " nodes");
		// Now link them up
		makeKleinbergNetwork(nodes, START_WITH_IDEAL_LOCATIONS, DEGREE, FORCE_NEIGHBOUR_CONNECTIONS, random);

		Logger.normal(RealNodeProbeTest.class, "Added random links");

		for(int i = 0; i < NUMBER_OF_NODES; i++) {
			System.err.println("Starting node " + i);
			nodes[i].start(false);
		}

		waitForAllConnected(nodes);

		final NumberFormat nf = NumberFormat.getInstance();
		Listener print = new Listener() {
			@Override
			public void onError(Error error, Byte code, boolean local) {
				System.out.print("Probe error: " + error.name());
				if (local) System.out.print(" (local)");
				System.out.println(code == null ? "" : " (" + code + ")");
			}

			@Override
			public void onRefused() {
				System.out.println("Probe refused.");
			}

			@Override
			public void onOutputBandwidth(float outputBandwidth) {
				System.out.println("Probe got bandwidth limit " + nf.format(outputBandwidth) +
					" KiB per second.");
			}

			@Override
			public void onBuild(int build) {
				System.out.println("Probe got build " + build + ".");
			}

			@Override
			public void onIdentifier(long identifier, byte uptimePercentage) {
				System.out.println("Probe got identifier " + identifier + " with uptime percentage " + uptimePercentage + ".");
			}

			@Override
			public void onLinkLengths(float[] linkLengths) {
				System.out.print("Probe got link lengths: { ");
				for (Float length : linkLengths) System.out.print(length + " ");
				System.out.println("}.");
			}

			@Override
			public void onLocation(float location) {
				System.out.println("Probe got location " + location + ".");
			}

			@Override
			public void onStoreSize(float storeSize) {
				System.out.println("Probe got store size " + nf.format(storeSize) + " GiB.");
			}

			@Override
			public void onUptime(float uptimePercentage) {
				System.out.print("Probe got uptime " + nf.format(uptimePercentage) + "%.");
			}
		};

		final Type types[] = {
			Type.BANDWIDTH,
			Type.BUILD,
			Type.IDENTIFIER,
			Type.LINK_LENGTHS,
			Type.LOCATION,
			Type.STORE_SIZE,
			Type.UPTIME_48H,
			Type.UPTIME_7D
		};

		int index = 0;
		byte htl = Probe.MAX_HTL;
		while (true) {
			System.out.println("Sending probes from node " + index + " with HTL " + htl + ".");
			System.out.println("0) BANDWIDTH");
			System.out.println("1) BUILD");
			System.out.println("2) IDENTIFIER");
			System.out.println("3) LINK_LENGTHS");
			System.out.println("4) LOCATION");
			System.out.println("5) STORE_SIZE");
			System.out.println("6) UPTIME 48-hour");
			System.out.println("7) UPTIME 7-day");
			System.out.println("8) Pick another node");
			System.out.println("9) Pick another HTL");
			System.out.println("10) Pick current node's refusals");
			System.out.println("Anything else to exit.");
			System.out.println("Select: ");
			try {
				int selection = Integer.valueOf(System.console().readLine());
				if (selection == 8) {
					System.out.print("Enter new node index ([0-" + (NUMBER_OF_NODES - 1) + "]):");
					index = Integer.valueOf(System.console().readLine());
				}
				else if (selection == 9) {
					System.out.print("Enter new HTL: ");
					htl = Byte.valueOf(System.console().readLine());
				} else if (selection == 10) {
					SubConfig nodeConfig = nodes[index].config.get("node");
					String[] options = { "probeBandwidth", "probeBuild", "probeIdentifier", "probeLinkLengths", "probeLinkLengths", "probeUptime" };
					for (String option : options) {
						System.out.print(option + ": ");
						nodeConfig.set(option, Boolean.valueOf(System.console().readLine()));
					}
				} else nodes[index].startProbe(htl, random.nextLong(), types[selection], print);
			} catch (Exception e) {
				//If a non-number is entered or one outside the bounds.
				System.out.print(e.toString());
				e.printStackTrace();
				//Return isn't enough to exit: the nodes are still in the background.
				System.exit(0);
			}
		}
	}
}
