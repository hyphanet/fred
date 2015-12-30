/** Supporting classes: Configuration framework. Probably we could switch to some standard system
 * one day. Most options can be changed on the fly, some require a restart. Config is the global 
 * config, divided up into SubConfig objects by subsystem. Option's are registered on the SubConfig
 * object with the appropriate ConfigCallback. WrapperConfig provides limited support for changing
 * config options that are kept in wrapper.conf (in collaboration with @link 
 * freenet.node.updater ). */
package freenet.config;