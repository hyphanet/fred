/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.SimpleFieldSet;

/**
 * Something that can be persisted to disk in the form of a SimpleFieldSet.
 */
public interface Persistable {

	SimpleFieldSet persistThrottlesToFieldSet();

}
