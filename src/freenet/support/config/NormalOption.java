package freenet.support.config;

public class NormalOption extends Option {

    boolean installation;
        
    /** Create a new option to register.
     * @param name          The name of the option.
     * @param abbrev        The abbreviated command-line switch.
     * @param numArgs       The number of arguments to read.
     * @param defaultValue  The option's default value.
     * @param sortOrder     Lower value means it is sorted earlier.
     * @param installation  Whether this is an installation setting
     */
    public NormalOption(String name, char abbrev, int numArgs, 
                        Object defaultValue, int sortOrder, boolean installation) {
        super(name, abbrev, numArgs, sortOrder);
        this.defaultClass = defaultValue.getClass();
        this.defaultValue = defaultValue;
        this.installation = installation;
    }

    public NormalOption(String name, char abbrev, int numArgs,
                        Class defaultClass, int sortOrder, boolean installation) {
        super(name, abbrev, numArgs, sortOrder);
        this.defaultClass = defaultClass;
        this.installation = installation;
    }

    public final Object defaultValue() {
        return defaultValue;
    }

    public final Class defaultClass() {
        return defaultClass;
    }

    public boolean isInstallation() {
        return installation;
    }
}








