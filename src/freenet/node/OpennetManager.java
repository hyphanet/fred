package freenet.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.support.SimpleFieldSet;

/**
 * Central location for all things opennet.
 * In particular:
 * - Opennet crypto
 * - LRU connections
 * @author toad
 */
public class OpennetManager {
	
	final Node node;
	final NodeCrypto crypto;

	public OpennetManager(Node node, NodeCryptoConfig opennetConfig) throws NodeInitException {
		this.node = node;
		crypto =
			new NodeCrypto(1 /* 0 is enabled */, node, true, opennetConfig);

		// Keep opennet crypto details in a separate file
		try {
			readFile(new File(node.nodeDir, "opennet-"+crypto.portNumber).getPath());
		} catch (IOException e) {
			try {
				readFile(new File("node-"+crypto.portNumber+".bak").getPath());
			} catch (IOException e1) {
				crypto.initCrypto();
			}
		}
		node.peers.tryReadPeers(new File(node.nodeDir, "openpeers-"+crypto.portNumber).toString(), crypto, true);
	}

	private void readFile(String filename) throws IOException {
		// REDFLAG: Any way to share this code with Node and NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br, false, true);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(int i=0;i<udp.length;i++) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udp[i], false);
				} catch (PeerParseException e) {
					IOException e1 = new IOException();
					e1.initCause(e);
					throw e1;
				}
				if(p.getPort() == crypto.portNumber) {
					// DNSRequester doesn't deal with our own node
					node.ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}
		
		crypto.readCrypto(fs);
	}

	public void start() {
		// FIXME do something
	}

	/**
	 * Called when opennet is disabled
	 */
	public void stop() {
		// FIXME do something
	}

}
