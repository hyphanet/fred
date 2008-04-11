/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.api;

import freenet.support.io.ArrayBucket;
import junit.framework.TestCase;

/**
 * Test case for {@link freenet.support.api.HTTPReply} class.
 * 
 * @author stuart martin &lt;wavey@freenetproject.org&gt;
 */
public class HttpReplyTest extends TestCase {

	public void testHttpReply(){
		
		String inMimeType = "text/plain";
		Bucket inBucket = new ArrayBucket();
		
		HTTPReply reply = new HTTPReply(inMimeType,inBucket);
		
		String outMimeType = reply.getMIMEType();
		Bucket outBucket = reply.getData();
		
		assertEquals(outMimeType, inMimeType);
		assertEquals(outBucket, inBucket);
	}
}
