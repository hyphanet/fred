/*
  ClientKSK.java / Freenet
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

package freenet.keys;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.MessageDigest;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.SHA256;

public class ClientKSK extends InsertableClientSSK {

	final String keyword;
	
	private ClientKSK(String keyword, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] keywordHash) throws MalformedURLException {
		super(keyword, pubKeyHash, pubKey, privKey, keywordHash);
		this.keyword = keyword;
	}

	public FreenetURI getURI() {
		return new FreenetURI("KSK", keyword);
	}
	
	public static InsertableClientSSK create(FreenetURI uri) {
		if(!uri.getKeyType().equals("KSK"))
			throw new IllegalArgumentException();
		return create(uri.getDocName());
	}
	
	public static ClientKSK create(String keyword) {
		MessageDigest md256 = SHA256.getMessageDigest();
		byte[] keywordHash;
		try {
			keywordHash = md256.digest(keyword.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		MersenneTwister mt = new MersenneTwister(keywordHash);
		DSAPrivateKey privKey = new DSAPrivateKey(Global.DSAgroupBigA, mt);
		DSAPublicKey pubKey = new DSAPublicKey(Global.DSAgroupBigA, privKey);
		byte[] pubKeyHash = md256.digest(pubKey.asBytes());
		try {
			return new ClientKSK(keyword, pubKeyHash, pubKey, privKey, keywordHash);
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}
	
}
