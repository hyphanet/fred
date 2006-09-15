/*
  NullBucket.java / Freenet
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

package freenet.support.io;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.SimpleFieldSet;

public class NullBucket implements Bucket, SerializableToFieldSetBucket {

    public final OutputStream nullOut = new NullOutputStream();
    public final InputStream  nullIn  = new NullInputStream();

    public final long length;
    
    public NullBucket() {
        this(0);
    }

    public NullBucket(long length) {
        this.length = length;
    }
    
    /**
     * Returns an OutputStream that is used to put data in this Bucket.
     **/
    public OutputStream getOutputStream() { return nullOut; }

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     **/
    public InputStream getInputStream() { return nullIn; }

    /**
     * If resetWrite() is called on the object, the next getOutputStream
     * should overwrite any other data in the bucket from the beginning,
     * otherwise it should append it.
     **/
    public void resetWrite() {}

    /**
     * Returns the amount of data currently in this bucket.
     **/
    public long size() {
        return length;
    }

    /** Returns the name of this NullBucket. */
    public String getName() {
    	return "President George W. NullBucket";
    }

	public boolean isReadOnly() {
		return false;
	}

	public void setReadOnly() {
		// Do nothing
	}

	public void free() {
		// Do nothing
	}

	public SimpleFieldSet toFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("Type", "NullBucket");
		return fs;
	}
}

