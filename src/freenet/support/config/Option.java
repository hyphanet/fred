package freenet.support.config;

public abstract class Option implements Comparable {
    
    String name;
    char abbrev;
    int numArgs;
    Class defaultClass;
    Object defaultValue;
    boolean isExpert = false;
    boolean isDeprecated = false;
    int sortOrder;

    String argDesc;
    String shortDesc;
    String[] longDesc;
        
    /** Create a new option to register.
     * @param name          The name of the option.
     * @param abbrev        The abbreviated command-line switch.
     * @param numArgs       The number of arguments to read.
     * @param sortOrder     Lower value means it is sorted earlier.
     */
    Option(String name, char abbrev, int numArgs, int sortOrder) {
        this.name = name;
        this.abbrev = abbrev;
        this.numArgs = numArgs;
        this.sortOrder = sortOrder;
    }

    public final String name() {
        return name;
    }
    
    public String getShortDesc(){
    	return shortDesc;
    }
	public String[] getLongDesc(){
		return longDesc;
	}

    public final char abbrev() {
        return abbrev;
    }
        
    public final int numArgs() {
        return numArgs;
    }

    /**
     * The default value of this object.
     */
    public abstract Object defaultValue();

    /**
     * The type of values that this object takes.
     */
    public abstract Class defaultClass();

    /**
     * Installation options are the type which varry with installations,
     * and so should always be written to the file, even if left unchanged.
     */
    public abstract boolean isInstallation();


    public final int compareTo(Object o) {
        int oso = ((Option) o).sortOrder;
        return sortOrder > oso ? 1 : oso == sortOrder ? 0 : -1; 
    }

    public final int getSortOrder() {
	return sortOrder;
    }


    // this is just another string hashcode, I just need it to be 
    // consistant.
    private static int getNum(String name) {
        byte[] b = name.getBytes();
        int r = 0, i;
        for (i = b.length - 1; i >= 3 ; i -= 4)
            r ^= b[i] << 24 | b[i-1] << 16 | b[i-2] << 8 | b[i-3];
        switch (i) {
        case 2:
            r ^= b[i-2];
        case 1:
            r ^= b[i-1] << 8; 
        case 0:
            r ^= b[i];
        }
        return r;
    }
    
    public String toString() {
	String s = defaultValue == null ? "(null)" : defaultValue.toString();
	StringBuffer sb = new StringBuffer((name == null ? 6 : name.length()) +
					   1 + 1 + 1 + (s == null ? 6 :	s.length())
					   + 5 + 1 + 5 + 2 + 1 + 1);
	sb.append(wrap(name));
	sb.append('/');
	if(abbrev != ((char)0)) sb.append(abbrev);
	sb.append('=');
	sb.append(s);
	sb.append('(');
	sb.append(numArgs);
	sb.append(',');
	sb.append(isExpert);
	sb.append(',');
	sb.append(sortOrder);
	sb.append(')');
	return sb.toString();
    }
    
    protected static String wrap(Object o) {
	return o == null ? "(null)" : o.toString();
    }
}








