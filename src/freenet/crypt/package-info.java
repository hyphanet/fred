/** 
 * Freenet encryption support code. Some of this will be replaced with JCA code, but JCA has export
 * restrictions issues (which are a massive configuration/maintenance problem for end users even if
 * they are US citizens). Some of it wraps JCA code to provide convenient APIs, boost performance 
 * by e.g. precomputing stuff, or support higher level functionality not provided by JCA (e.g. JFK
 * connection setup). Much of it is a bit too low level. 
 */
package freenet.crypt;