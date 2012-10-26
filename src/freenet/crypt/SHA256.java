/**
Cryptix General Licence
Copyright (C) 1995, 1996, 1997, 1998, 1999, 2000 
The Cryptix Foundation Limited. All rights reserved.
Redistribution and use in source and binary forms, with or without 
modification, are permitted provided that the following conditions 
are met:
1. Redistributions of source code must retain the copyright notice, 
this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright 
notice, this list of conditions and the following disclaimer in 
the documentation and/or other materials provided with the 
distribution.
THIS SOFTWARE IS PROVIDED BY THE CRYPTIX FOUNDATION LIMITED ``AS IS'' 
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A 
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR 
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, 
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT 
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF 
USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED 
AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT 
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Copyright (C) 2000 The Cryptix Foundation Limited. All rights reserved.
 *
 * Use, modification, copying and distribution of this software is subject to
 * the terms and conditions of the Cryptix General Licence. You should have
 * received a copy of the Cryptix General Licence along with this library;
 * if not, you can download a copy from http://www.cryptix.org/ .
 */
package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.ArrayList;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;

/**
 * @author  Jeroen C. van Gelderen (gelderen@cryptix.org)
 */
public class SHA256 {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/** Size (in bytes) of this hash */
	private static final int HASH_SIZE = 32;

	private static final int MESSAGE_DIGESTS_TO_CACHE = 16;
	private static final ArrayList<MessageDigest> digests = new ArrayList<MessageDigest>();

	/**
	 * It won't reset the Message Digest for you!
	 * @param InputStream
	 * @param MessageDigest
	 * @return
	 * @throws IOException
	 */
	public static void hash(InputStream is, MessageDigest md) throws IOException {
		try {
			byte[] buf = new byte[4096];
			int readBytes = is.read(buf);
			while(readBytes > -1) {
				md.update(buf, 0, readBytes);
				readBytes = is.read(buf);
			}
			is.close();
		} finally {
			Closer.close(is);
		}
	}

	private static final Provider mdProvider = Util.mdProviders.get("SHA-256");

	/**
	 * Create a new SHA-256 MessageDigest
	 * Either succeed or stop the node.
	 */
	public static MessageDigest getMessageDigest() {
		try {
			MessageDigest md = null;
			synchronized(digests) {
				int x = digests.size();
				if(x == 0) md = null;
				else md = digests.remove(x-1);
			}
			if(md == null)
				md = MessageDigest.getInstance("SHA-256", mdProvider);
			return md;
		} catch(NoSuchAlgorithmException e2) {
			//TODO: maybe we should point to a HOWTO for freejvms
			Logger.error(Node.class, "Check your JVM settings especially the JCE!" + e2);
			System.err.println("Check your JVM settings especially the JCE!" + e2);
			e2.printStackTrace();
		}
		WrapperManager.stop(NodeInitException.EXIT_CRAPPY_JVM);
		throw new RuntimeException();
	}

	/**
	 * Return a MessageDigest to the pool.
	 * Must be SHA-256 !
	 */
	public static void returnMessageDigest(MessageDigest md256) {
		if(md256 == null)
			return;
		String algo = md256.getAlgorithm();
		if(!(algo.equals("SHA-256") || algo.equals("SHA256")))
			throw new IllegalArgumentException("Should be SHA-256 but is " + algo);
		md256.reset();
		synchronized (digests) {
			int mdPoolSize = digests.size();
			if (mdPoolSize > MESSAGE_DIGESTS_TO_CACHE || noCache) { // don't cache too many of them
				if(logMINOR) Logger.normal(SHA256.class, "Throwing away a SHA256 MessageDigest ("+mdPoolSize+'>'+MESSAGE_DIGESTS_TO_CACHE+')');
				return;
			}
			digests.add(md256);
		}
	}

	public static byte[] digest(byte[] data) {
		MessageDigest md = getMessageDigest();
		byte[] hash = md.digest(data);
		returnMessageDigest(md);
		return hash;
	}

	public static int getDigestLength() {
		return HASH_SIZE;
	}
	
	private static boolean noCache = false;
	
	static {
		OOMHandler.addOOMHook(new OOMHook() {
			@Override
			public void handleLowMemory() throws Exception {
				synchronized(digests) {
					digests.clear();
				}
				noCache = true;
			}

			@Override
			public void handleOutOfMemory() throws Exception {
				synchronized(digests) {
					digests.clear();
				}
				noCache = true;
			}
		});
	}
}
