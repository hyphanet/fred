/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.config;

//~--- non-JDK imports --------------------------------------------------------

import freenet.l10n.NodeL10n;

import freenet.support.Fields;
import freenet.support.api.ShortCallback;

public class ShortOption extends Option<Short> {
    protected final boolean isSize;

    public ShortOption(SubConfig conf, String optionName, short defaultValue, int sortOrder, boolean expert,
                       boolean forceWrite, String shortDesc, String longDesc, ShortCallback cb, boolean isSize) {
        super(conf, optionName, cb, sortOrder, expert, forceWrite, shortDesc, longDesc, Option.DataType.NUMBER);
        this.defaultValue = defaultValue;
        this.currentValue = defaultValue;
        this.isSize = isSize;
    }

    private String l10n(String key, String pattern, String value) {
        return NodeL10n.getBase().getString("ShortOption." + key, pattern, value);
    }

    @Override
    protected Short parseString(String val) throws InvalidConfigValueException {
        short x;

        try {
            x = Fields.parseShort(val);
        } catch (NumberFormatException e) {
            throw new InvalidConfigValueException(l10n("unrecognisedShort", "val", val));
        }

        return x;
    }

    @Override
    protected String toString(Short val) {
        return Fields.shortToString(val, isSize);
    }
}
