/**
 * Freenet Client Protocol support. This is how the node talks to external
 * clients (outside the JVM). Lets the client layer do as much of the work 
 * as possible and avoids exposing block level details to the clients, 
 * while providing plenty of status info, to avoid clients implementing 
 * their own mutually incompatible metadata schemes when this can be 
 * avoided. (This does not mean we should never let clients have access to
 * blocks, of course). Supports most functionality, including stats, 
 * darknet peers, persistent downloads etc.
 * 
 * Some of the messages have support for parsing as a client, some code in 
 * @see freenet.tools uses this, but we don't have a proper official Java 
 * FCP library.
 * 
 * FIXME this should be moved to top-level freenet.fcp however FCPClient 
 * is persistent in node.db4o so that will break backward compaibility on
 * databases unless migration code is put in.
 */
package freenet.node.fcp;
