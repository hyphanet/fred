/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

/**
 * Something that might have a CooldownTrackerItem associated with it. We don't just use
 * SendableGet because e.g. SplitFileFetcherSegment isn't one.
 * @author toad
 */
public interface HasCooldownTrackerItem {

    /** Construct a new CooldownTrackerItem. Called inside CooldownTracker lock. */
    CooldownTrackerItem makeCooldownTrackerItem();
}
