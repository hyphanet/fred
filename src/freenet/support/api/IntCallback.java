/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import freenet.config.ConfigCallback;

/**
 * A callback to be called when a config value of integer type changes.
 * Also reports the current value.
 */
public abstract class IntCallback extends ConfigCallback<Integer> {
}
