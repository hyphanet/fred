/*
  ToadletContext.java / Freenet
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

package freenet.clients.http;

import java.io.IOException;

import freenet.support.MultiValueTable;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;

/**
 * Object represents context for a single request. Is used as a token,
 * when the Toadlet wants to e.g. write a reply.
 */
public interface ToadletContext {

	/**
	 * Write reply headers.
	 * @param code HTTP code.
	 * @param desc HTTP code description.
	 * @param mvt Any extra headers.
	 * @param mimeType The MIME type of the reply.
	 * @param length The length of the reply.
	 */
	void sendReplyHeaders(int code, String desc, MultiValueTable mvt, String mimeType, long length) throws ToadletContextClosedException, IOException;

	/**
	 * Write data. Note you must send reply headers first.
	 */
	void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException;

	/**
	 * Convenience method that simply calls {@link #writeData(byte[], int, int)}.
	 * 
	 * @param data
	 *            The data to write
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	void writeData(byte[] data) throws ToadletContextClosedException, IOException;

	/**
	 * Write data from a bucket. You must send reply headers first.
	 */
	void writeData(Bucket data) throws ToadletContextClosedException, IOException;
	
	/**
	 * Get the page maker object.
	 */
	PageMaker getPageMaker();

	BucketFactory getBucketFactory();
	
	MultiValueTable getHeaders();
}

