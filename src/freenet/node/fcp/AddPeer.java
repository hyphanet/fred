package freenet.node.fcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import freenet.io.comm.PeerParseException;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class AddPeer extends FCPMessage {

	static final String name = "AddPeer";
	
	SimpleFieldSet fs;
	
	public AddPeer(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(false);
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		String urlString = fs.get("URL");
		String fileString = fs.get("File");
		String ref = null;
		BufferedReader in;
		if(urlString != null) {
			try {
				URL url = new URL(urlString);
				URLConnection uc = url.openConnection();
				in = new BufferedReader( new InputStreamReader(uc.getInputStream()));
				ref = "";
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					ref += line+"\n";
				}
				in.close();
			} catch (MalformedURLException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			} catch (IOException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			}
			ref = ref.trim();
			if(ref == null) return;  // **FIXME** FCPify
			if(ref.equals("")) return;  // **FIXME** FCPify
			try {
				fs = new SimpleFieldSet(ref, true);
			} catch (IOException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			}
		} else if(fileString != null) {
			File f = new File(fileString);
			if(!f.isFile()) {
				// **FIXME** FCPify
				System.err.println("Not a file: "+fileString);
				return;
			}
			try {
				in = new BufferedReader(new FileReader(f));
				ref = "";
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					ref += line+"\n";
				}
				in.close();
			} catch (FileNotFoundException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			} catch (IOException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			}
			ref = ref.trim();
			if(ref == null) return;  // **FIXME** FCPify
			if(ref.equals("")) return;  // **FIXME** FCPify
			try {
				fs = new SimpleFieldSet(ref, true);
			} catch (IOException e) {
				// **FIXME** FCPify
				System.err.println("Did not parse: "+e);
				e.printStackTrace();
				return;
			}
		}
		PeerNode pn;
		try {
			pn = new PeerNode(fs, node, false);
		} catch (FSParseException e1) {
			// **FIXME** FCPify
			System.err.println("Did not parse: "+e1);
			Logger.error(this, "Did not parse: "+e1, e1);
			return;
		} catch (PeerParseException e1) {
			// **FIXME** FCPify
			System.err.println("Did not parse: "+e1);
			Logger.error(this, "Did not parse: "+e1, e1);
			return;
		}
		// **FIXME** Handle duplicates somehow maybe?  What about when node.addDarknetConnection() fails for some reason?
		if(node.addDarknetConnection(pn))
			System.out.println("Added peer: "+pn);
		handler.outputHandler.queue(new Peer(pn, true, true));
	}

}
