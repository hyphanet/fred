/** Datastore implementations. These classes store key:block pairs, either on disk or in memory.
 * This package provides adapters to store different types of blocks (SSKs, CHKs, pubkeys), with
 * each actual store storing one key type, but the node itself will have multiple such stores,
 * for different purposes (e.g. short term caching vs long term storage).
 */
package freenet.store;
