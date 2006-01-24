package freenet.support;

import java.util.Comparator;

public class SimpleIntNumberedItemComparator implements Comparator {

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
        if(o1 instanceof IntNumberedItem)
            i1 = ((IntNumberedItem)o1).getNumber();
        else if(o1 instanceof Integer)
            i1 = ((Integer)o1).intValue();
        else throw new ClassCastException(o1.toString());
        if(o2 instanceof IntNumberedItem)
            i2 = ((IntNumberedItem)o2).getNumber();
        else if(o2 instanceof Integer)
            i2 = ((Integer)o2).intValue();
        else throw new ClassCastException(o2.toString());
        if(i1 == i2) return 0;
        if(i1 > i2) return 1;
        else return -1;
    }
    

}
