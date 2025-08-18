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

import freenet.support.io.Closer;

/**
 * @author  Jeroen C. van Gelderen (gelderen@cryptix.org)
 */
public class SHA256 {

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

	/**
	 * Create a new SHA-256 MessageDigest
	 */
	public static MessageDigest getMessageDigest() {
		return HashType.SHA256.get();
	}

	/**
	 * No-op function retained for backwards compatibility.
	 *
	 * @deprecated message digests are no longer pooled, there is no need to return them
	 */
	@Deprecated
	public static void returnMessageDigest(MessageDigest md256) {
	}

	public static byte[] digest(byte[] data) {
		return getMessageDigest().digest(data);
	}

	public static int getDigestLength() {
		return HashType.SHA256.hashLength;
	}
}
