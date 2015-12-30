/** Block transfers, bulk transfers and (part of) congestion control. A block is a CHK, 32KB 
 * divided into 1kB packets. Bulk is anything else, e.g. opennet node references, friend-to-friend 
 * file transfers. The block transfer code was originally from Dijjer but has been substantially 
 * rewritten as the lower levels provide guaranteed delivery of @link freenet.io.comm.Message 's.
 * The other parts of congestion control are in freenet.node.
 */
package freenet.io.xfer;