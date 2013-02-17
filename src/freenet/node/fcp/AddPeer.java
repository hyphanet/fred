/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.DarknetPeerNode.FRIEND_VISIBILITY;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.OpennetDisabledException;
import freenet.node.PeerNode;
import freenet.support.MediaType;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;

public class AddPeer extends FCPMessage {

	public static final String NAME = "AddPeer";
	
	SimpleFieldSet fs;
	final String identifier;
	final FRIEND_TRUST trust;
	final FRIEND_VISIBILITY visibility;
	
	public AddPeer(SimpleFieldSet fs) throws MessageInvalidException {
		this.fs = fs;
		this.identifier = fs.get("Identifier");
		fs.removeValue("Identifier");
		try {
			this.trust = FRIEND_TRUST.valueOf(fs.get("Trust"));
		} catch (NullPointerException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "AddPeer requires Trust", identifier, false);
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid Trust value on AddPeer", identifier, false);
		}
		try {
			this.visibility = FRIEND_VISIBILITY.valueOf(fs.get("Visibility"));
		} catch (NullPointerException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "AddPeer requires Visibility", identifier, false);
		} catch (IllegalArgumentException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid Visibility value on AddPeer", identifier, false);
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return NAME;
	}
	
	public static StringBuilder getReferenceFromURL(URL url) throws IOException {
		StringBuilder ref = new StringBuilder(1024);
		InputStream is = null;
		try {
			URLConnection uc = url.openConnection();
			is = uc.getInputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(is, MediaType.getCharsetRobustOrUTF(uc.getContentType())));
			String line;
			while ((line = in.readLine()) != null) {
				ref.append( line ).append('\n');
			}
			return ref;
		} finally {
			Closer.close(is);
		}
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "AddPeer requires full access", identifier, false);
		}
		String urlString = fs.get("URL");
		String fileString = fs.get("File");
		StringBuilder ref = null;
		BufferedReader in;
		if(urlString != null) {
			try {
				URL url = new URL(urlString);
				ref = getReferenceFromURL(url);
			} catch (MalformedURLException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.URL_PARSE_ERROR, "Error parsing ref URL <"+urlString+">: "+e.getMessage(), identifier, false);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.URL_PARSE_ERROR, "IO error while retrieving ref URL <"+urlString+">: "+e.getMessage(), identifier, false);
			}
			ref = new StringBuilder(ref.toString().trim());
			if("".equals(ref.toString())) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from URL <"+urlString+ '>', identifier, false);
			}
			try {
				fs = new SimpleFieldSet(ref.toString(), false, true);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from URL <"+urlString+">: "+e.getMessage(), identifier, false);
			}
		} else if(fileString != null) {
			File f = new File(fileString);
			if(!f.isFile()) {
				throw new MessageInvalidException(ProtocolErrorMessage.NOT_A_FILE_ERROR, "The given ref file path <"+fileString+"> is not a file", identifier, false);
			}
			try {
				in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
				ref = new StringBuilder(1024);
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					ref.append( line ).append('\n');
				}
				in.close();
			} catch (FileNotFoundException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "File not found when retrieving ref file <"+fileString+">: "+e.getMessage(), identifier, false);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_PARSE_ERROR, "IO error while retrieving ref file <"+fileString+">: "+e.getMessage(), identifier, false);
			}
			ref = new StringBuilder(ref.toString().trim());
			if("".equals(ref.toString())) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from file <"+fileString+ '>', identifier, false);
			}
			try {
				fs = new SimpleFieldSet(ref.toString(), false, true);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from file <"+fileString+">: "+e.getMessage(), identifier, false);
			}
		}
		fs.setEndMarker( "End" );
		PeerNode pn;
		boolean isOpennetRef = fs.getBoolean("opennet", false);
		if(isOpennetRef) {
			try {
				pn = node.createNewOpennetNode(fs);
			} catch (FSParseException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref: "+e.getMessage(), identifier, false);
			} catch (OpennetDisabledException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.OPENNET_DISABLED, "Error adding ref: "+e.getMessage(), identifier, false);
			} catch (PeerParseException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref: "+e.getMessage(), identifier, false);
			} catch (ReferenceSignatureVerificationException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_SIGNATURE_INVALID, "Error adding ref: "+e.getMessage(), identifier, false);
			}
			if(Arrays.equals(pn.getPubKeyHash(), node.getOpennetPubKeyHash()))
				throw new MessageInvalidException(ProtocolErrorMessage.CANNOT_PEER_WITH_SELF, "Node cannot peer with itself", identifier, false);
			if(!node.addPeerConnection(pn)) {
				throw new MessageInvalidException(ProtocolErrorMessage.DUPLICATE_PEER_REF, "Node already has a peer with that identity", identifier, false);
			}
			System.out.println("Added opennet peer: "+pn);
		} else {
			try {
				pn = node.createNewDarknetNode(fs, trust, visibility);
			} catch (FSParseException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref: "+e.getMessage(), identifier, false);
			} catch (PeerParseException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref: "+e.getMessage(), identifier, false);
			} catch (ReferenceSignatureVerificationException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_SIGNATURE_INVALID, "Error adding ref: "+e.getMessage(), identifier, false);
			}
			if(Arrays.equals(pn.getPubKeyHash(), node.getDarknetPubKeyHash()))
				throw new MessageInvalidException(ProtocolErrorMessage.CANNOT_PEER_WITH_SELF, "Node cannot peer with itself", identifier, false);
			if(!node.addPeerConnection(pn)) {
				throw new MessageInvalidException(ProtocolErrorMessage.DUPLICATE_PEER_REF, "Node already has a peer with that identity", identifier, false);
			}
			System.out.println("Added darknet peer: "+pn);
		}
		handler.outputHandler.queue(new PeerMessage(pn, true, true, identifier));
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
