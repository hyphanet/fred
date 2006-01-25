package freenet.support;

import java.util.Comparator;

public class NumberedItemComparator implements Comparator {

	public NumberedItemComparator(boolean wrap) {
		this.wrapAround = wrap;
	}
	
	final boolean wrapAround;
	
    public int compare(Object o1, Object o2) {
        int x = ocompare(o1, o2);
        Logger.minor(this, "compare("+o1+","+o2+") = "+x);
        return x;
    }
    
    public int ocompare(Object o1, Object o2) {
        // Nulls at the end of the list
        if(o1 == null && o2 == null)
            return 0; // null == null
        if(o1 != null && o2 == null)
            return 1; // anything > null
        if(o2 != null && o1 == null)
            return -1;
        long i1, i2;
        if(o1 instanceof NumberedItem)
            i1 = ((NumberedItem)o1).getNumber();
        else if(o1 instanceof Long)
            i1 = ((Long)o1).longValue();
        else throw new ClassCastException(o1.toString());
        if(o2 instanceof NumberedItem)
            i2 = ((NumberedItem)o2).getNumber();
        else if(o2 instanceof Long)
            i2 = ((Long)o2).longValue();
        else throw new ClassCastException(o2.toString());
        if(i1 == i2) return 0;
        if(!wrapAround) {
            if(i1 > i2) return 1;
            else return -1;
        } else {
            long firstDistance, secondDistance;
            if(i1 > i2) {
                firstDistance = i1 - i2; // smaller => i1 > i2
                secondDistance = i2 + Long.MAX_VALUE - i1; // smaller => i2 > i1
            } else {
                secondDistance = i2 - i1; // smaller => i2 > i1
                firstDistance = i1 + Long.MAX_VALUE - i2; // smaller => i1 > i2
            }
            if(Math.abs(firstDistance) < Math.abs(secondDistance)) {
                return 1; // i1>i2
            } else //if(Math.abs(secondDistance) < Math.abs(firstDistance)) {
                return -1; // i2>i1
            // REDFLAG: base must be odd, so we never get ==
        }
    }
    
}
