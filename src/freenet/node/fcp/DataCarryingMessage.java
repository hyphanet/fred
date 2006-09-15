/*
  DataCarryingMessage.java / Freenet
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.Logger;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;
import freenet.support.io.BucketTools;


public abstract class DataCarryingMessage extends BaseDataCarryingMessage {

	protected Bucket bucket;
	
	Bucket createBucket(BucketFactory bf, long length, FCPServer server) throws IOException {
		return bf.makeBucket(length);
	}
	
	abstract String getIdentifier();

	protected boolean freeOnSent;
	
	void setFreeOnSent() {
		freeOnSent = true;
	}

	public void readFrom(InputStream is, BucketFactory bf, FCPServer server) throws IOException, MessageInvalidException {
		long len = dataLength();
		if(len < 0)
			throw new IllegalArgumentException("Invalid length: "+len);
		if(len == 0) return;
		Bucket tempBucket;
		try {
			tempBucket = createBucket(bf, len, server);
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e, e);
			throw new MessageInvalidException(ProtocolErrorMessage.INTERNAL_ERROR, e.toString(), getIdentifier());
		}
		BucketTools.copyFrom(tempBucket, is, len);
		this.bucket = tempBucket;
	}
	
	protected void writeData(OutputStream os) throws IOException {
		BucketTools.copyTo(bucket, os, dataLength());
		if(freeOnSent) bucket.free();
	}
	
	String getEndString() {
		return "Data";
	}
	
}
