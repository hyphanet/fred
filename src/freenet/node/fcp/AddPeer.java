/*
  AddPeer.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class AddPeer extends FCPMessage {

	public static final String name = "AddPeer";
	
	SimpleFieldSet fs;
	
	public AddPeer(SimpleFieldSet fs) {
		this.fs = fs;
	}

	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet();
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		String urlString = fs.get("URL");
		String fileString = fs.get("File");
		StringBuffer ref = null;
		BufferedReader in;
		if(urlString != null) {
			try {
				URL url = new URL(urlString);
				URLConnection uc = url.openConnection();
				in = new BufferedReader( new InputStreamReader(uc.getInputStream()));
				ref = new StringBuffer(1024);
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					ref.append( line ).append( "\n" );
				}
				in.close();
			} catch (MalformedURLException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.URL_PARSE_ERROR, "Error parsing ref URL <"+urlString+">: "+e.getMessage(), null);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.URL_PARSE_ERROR, "IO error while retrieving ref URL <"+urlString+">: "+e.getMessage(), null);
			}
			ref = new StringBuffer(ref.toString().trim());
			if(ref == null) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from URL <"+urlString+">", null);
			}
			if(ref.equals("")) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from URL <"+urlString+">", null);
			}
			try {
				fs = new SimpleFieldSet(ref.toString(), true);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from URL <"+urlString+">: "+e.getMessage(), null);
			}
		} else if(fileString != null) {
			File f = new File(fileString);
			if(!f.isFile()) {
				throw new MessageInvalidException(ProtocolErrorMessage.NOT_A_FILE_ERROR, "The given ref file path <"+fileString+"> is not a file", null);
			}
			try {
				in = new BufferedReader(new FileReader(f));
				ref = new StringBuffer(1024);
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					ref.append( line ).append( "\n" );
				}
				in.close();
			} catch (FileNotFoundException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "File not found when retrieving ref file <"+fileString+">: "+e.getMessage(), null);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_PARSE_ERROR, "IO error while retrieving ref file <"+fileString+">: "+e.getMessage(), null);
			}
			ref = new StringBuffer(ref.toString().trim());
			if(ref == null) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from file <"+fileString+">", null);
			}
			if(ref.equals("")) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from file <"+fileString+">", null);
			}
			try {
				fs = new SimpleFieldSet(ref.toString(), true);
			} catch (IOException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing ref from file <"+fileString+">: "+e.getMessage(), null);
			}
		}
		PeerNode pn;
		try {
			pn = new PeerNode(fs, node, false);
		} catch (FSParseException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing retrieved ref: "+e.getMessage(), null);
		} catch (PeerParseException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing retrieved ref: "+e.getMessage(), null);
		} catch (ReferenceSignatureVerificationException e) {
			// TODO: maybe a special ProtocolErrorMessage ?
			throw new MessageInvalidException(ProtocolErrorMessage.REF_PARSE_ERROR, "Error parsing retrieved ref: "+e.getMessage(), null);
		}
		// **FIXME** Handle duplicates somehow maybe?  What about when node.addDarknetConnection() fails for some reason?
		if(node.addDarknetConnection(pn))
			System.out.println("Added peer: "+pn);
		handler.outputHandler.queue(new Peer(pn, true, true));
	}

}
