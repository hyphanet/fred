package freenet.clients.http;

import java.io.IOException;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.MultiValueTable;

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

