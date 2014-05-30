/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.Fields;
import freenet.support.api.IntCallback;

/**
 * Integer option for bandwidth. Allows "/s" and similar "per second" qualifiers.
 *
 * @see Fields#trimPerSecond(String)
 */
public class BandwidthOption extends IntOption {
    public BandwidthOption(SubConfig conf, String optionName, Integer defaultValue, int sortOrder, boolean expert,
                           boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
        super(conf, optionName, defaultValue, sortOrder, expert, forceWrite, shortDesc, longDesc, cb, true);
    }

    public BandwidthOption(SubConfig conf, String optionName, String defaultValueString, int sortOrder, boolean expert,
                           boolean forceWrite, String shortDesc, String longDesc, IntCallback cb) {
        this(conf, optionName, Fields.parseInt(defaultValueString), sortOrder, expert, forceWrite, shortDesc, longDesc,
             cb);
    }

    @Override
    protected Integer parseString(String val) throws InvalidConfigValueException {
        return super.parseString(Fields.trimPerSecond(val));
    }
}
