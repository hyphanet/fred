/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

/**
 * A null Object. Used where Object would be used, but can't be, because of db4o's inability
 * to store raw Object's. Usually this is used for synchronization in dual-use (persistent or
 * not) classes.
 *
 * See http://tracker.db4o.com/browse/COR-1314
 * @author toad
 */

//WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class NullObject {

    // Nothing
}
