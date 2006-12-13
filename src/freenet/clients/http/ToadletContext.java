package freenet.clients.http;

import java.io.IOException;

import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.Bucket;
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

	/**
	 * Add a form node to an HTMLNode under construction. This will have the correct enctype and 
	 * formPassword set already, so all the caller needs to do is add its specific fields.
	 * @param parentNode The parent HTMLNode.
	 * @param target Where the form should be POSTed to.
	 * @param id HTML name for the form for stylesheet/script access. Will be added as both id and name.
	 * @return The form HTMLNode.
	 */
	HTMLNode addFormChild(HTMLNode parentNode, String target, String id);
}

