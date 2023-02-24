/*
 * freenet - Closer.java Copyright © 2007 David Roden
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.io;


/**
 * Closes various resources. The resources are checked for being
 * <code>null</code> before being closed, and every possible execption is
 * swallowed. That makes this class perfect for use in the finally blocks of
 * try-catch-finally blocks.
 * 
 * @author David &lsquo;Roden&rsquo; &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 * @deprecated Java 7 has a new language feature which mostly does what this class was for:
 *             <a href="http://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html">The try with-resources Statement</a>.<br/>
 *             There are some differences with regards to swallowing Exceptions, please study them carefully when replacing Closer usage with it.
 */
@Deprecated
public class Closer {
}
