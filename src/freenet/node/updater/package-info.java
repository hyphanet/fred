/** Auto-update code for Freenet. Downloads new versions of Freenet over
 * Freenet, checks the signature, and deploys it by updating the jars,
 * if necessary updating wrapper.conf, and restarting using the Java
 * Service Wrapper. Supports updating multiple jar files. Requires the
 * wrapper to work.
 */
package freenet.node.updater;
