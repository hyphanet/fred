package freenet.node;

import java.io.File;

/**
 * Mapping of files managed by the node to their respective locations.
 */
public enum NodeFile {
    Seednodes(InstallDirectory.Node, "seednodes.fref"),
    InstallerWindows(InstallDirectory.Run, "freenet-latest-installer-windows.exe"),
    InstallerNonWindows(InstallDirectory.Run, "freenet-latest-installer-nonwindows.jar"),
    IPv4ToCountry(InstallDirectory.Run, "IpToCountry.dat");

    private final InstallDirectory dir;
    private final String filename;

    /**
     * Gets the absolute file path associated with this file for the given node instance.
     */
    public File getFile(Node node) {
        return dir.getDir(node).file(filename);
    }

    /**
     * Gets the filename associated with this file.
     */
    public String getFilename() {
        return filename;
    }

    /**
     * Gets the base directory with this file for the given node instance.
     */
    public ProgramDirectory getProgramDirectory(Node node) {
        return dir.getDir(node);
    }

    private NodeFile(InstallDirectory dir, String filename) {
        this.dir = dir;
        this.filename = filename;
    }

    private enum InstallDirectory {
        // node.install.nodeDir
        Node() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.nodeDir();
            }
        },
        // node.install.cfgDir
        Cfg() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.cfgDir();
            }
        },
        // node.install.userDir
        User() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.userDir();
            }
        },
        // node.install.runDir
        Run() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.runDir();
            }
        },
        // node.install.storeDir
        Store() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.storeDir();
            }
        },
        // node.install.pluginDir
        Plugin() {
            @Override
            ProgramDirectory getDir(Node node) {
                return node.pluginDir();
            }
        };

        abstract ProgramDirectory getDir(Node node);
    }
}
