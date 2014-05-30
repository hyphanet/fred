/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.support.Logger.LogLevel;

//~--- JDK imports ------------------------------------------------------------

import java.util.HashMap;

@SuppressWarnings("serial")
public class DebuggingHashMap<K extends Object, V extends Object> extends HashMap<K, V> {
    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {
            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

    public boolean objectCanUpdate(ObjectContainer container) {
        if (logMINOR) {
            Logger.minor(this,
                         "objectCanUpdate() on DebuggingHashMap " + this + " stored=" + container.ext().isStored(this)
                         + " active=" + container.ext().isActive(this) + " size=" + size(), new Exception("debug"));
        }

        return true;
    }

    public boolean objectCanNew(ObjectContainer container) {
        if (logMINOR) {
            Logger.minor(this,
                         "objectCanNew() on DebuggingHashMap " + this + " stored=" + container.ext().isStored(this)
                         + " active=" + container.ext().isActive(this) + " size=" + size(), new Exception("debug"));
        }

        return true;
    }

    public void objectOnUpdate(ObjectContainer container) {
        if (logMINOR) {
            Logger.minor(this,
                         "objectOnUpdate() on DebuggingHashMap " + this + " stored=" + container.ext().isStored(this)
                         + " active=" + container.ext().isActive(this) + " size=" + size(), new Exception("debug"));
        }
    }

    public void objectOnNew(ObjectContainer container) {
        if (logMINOR) {
            Logger.minor(this,
                         "objectOnNew() on DebuggingHashMap " + this + " stored=" + container.ext().isStored(this)
                         + " active=" + container.ext().isActive(this) + " size=" + size(), new Exception("debug"));
        }
    }

    // private transient boolean activating = false;
    public boolean objectCanActivate(ObjectContainer container) {
        if (logMINOR) {
            Logger.minor(this,
                         "objectCanActivate() on DebuggingHashMap stored=" + container.ext().isStored(this)
                         + " active=" + container.ext().isActive(this) + " size=" + size(), new Exception("debug"));
        }

        /** FIXME: This was an attempt to ensure we always activate to depth 2. It didn't work. :( */

//      if(activating) {
//              activating = false;
//              return true;
//      }
//      activating = true;
//      container.activate(this, 2);
//      return false;
        return true;
    }
}
