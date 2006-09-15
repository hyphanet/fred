/*
  Bucket.java / Freenet
  Copyright (C) oskar
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
import java.io.*;
/**
 * A bucket is any arbitrary object can temporarily store data.
 * 
 * @author oskar
 */
public interface Bucket {

    /**
     * Returns an OutputStream that is used to put data in this Bucket, from the 
     * beginning. It is not possible to append data to a Bucket! This simplifies the
     * code significantly for some classes. If you need to append, just pass the 
     * OutputStream around.
     */
    public OutputStream getOutputStream() throws IOException;

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     */
    public InputStream getInputStream() throws IOException;

    /**
     * Returns a name for the bucket, may be used to identify them in
     * certain in certain situations.
     */
    public String getName();

    /**
     * Returns the amount of data currently in this bucket.
     */
    public long size();

    /**
     * Is the bucket read-only?
     */
    public boolean isReadOnly();
    
    /**
     * Make the bucket read-only. Irreversible.
     */
    public void setReadOnly();

    /**
     * Free the bucket, if supported.
     */
	public void free();

}
