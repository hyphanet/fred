/**
 * Package for plugin-safe packages and interfaces which are also used by the rest of
 * the node, e.g. Bucket. Generally this will be interfaces, but there may be a few
 * classes too. Anything here must not support more functionality than is really
 * necessary; it must not be possible for a plugin to escalate its privelidges
 * through this API.
 */
package freenet.support.api;