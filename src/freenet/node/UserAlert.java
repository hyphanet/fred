package freenet.node;

/**
 * Class representing a critical news item that must be shown to the user.
 * 
 * @author amphibian
 */
public class UserAlert {
    /** Identifier, comes from the static values below */
    int id;
    /** Text to display (prominently) to the user */
    String text;
    /** Whether the user can simply acknowledge the alert and it go away.
     * If this is false then it will go away when its author withdraws it.
     */
    boolean cancellable;
    
    // Standard IDs
    public static final int ID_CANNOT_WRITE_CONFIG_FILE = 1;
    public static final int ID_OUT_OF_DISK_SPACE = 2;
    public static final int ID_CANNOT_RECEIVE_CONNECTIONS = 3;
    public static final int ID_EMPTY_ROUTING_TABLE = 4;
    // Add more here
}
