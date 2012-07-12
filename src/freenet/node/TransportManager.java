package freenet.node;

import java.net.UnknownHostException;
import java.util.HashMap;

import freenet.pluginmanager.FaultyTransportPluginException;
import freenet.pluginmanager.MalformedPluginAddressException;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PacketTransportPluginFactory;
import freenet.pluginmanager.StreamTransportPlugin;
import freenet.pluginmanager.StreamTransportPluginFactory;
import freenet.pluginmanager.TransportConfig;
import freenet.pluginmanager.TransportInitException;
import freenet.pluginmanager.TransportPluginConfigurationException;
import freenet.pluginmanager.TransportPluginException;
import freenet.support.Logger;
/**
 * This class maintains a record of packet transports and stream transports available. 
 * For every mode(opennet, darknet, etc.) a separate manager is created at the node
 * The plugin lets the node know about its presence by registering here with either a packet transport or a stream transport
 * The register method calls the corresponding NodeCrypto object to handle the new transport.
 * In case the NodeCrpyto is non-existent, 
 * then on creation it can get a list of available transports and call the initPlugin method.
 * @author chetan
 *
 */
public class TransportManager {
	
	private final Node node;
	
	private final TransportManagerConfig transportManagerConfig;
	
	/** The mode of operation - opennet, darknet, etc. */
	public enum TransportMode{
		opennet, darknet
	};
	public final TransportMode transportMode;
	
	private HashMap<String, PacketTransportPlugin> packetTransportMap = new HashMap<String, PacketTransportPlugin> ();
	private HashMap<String, StreamTransportPlugin> streamTransportMap = new HashMap<String, StreamTransportPlugin> ();
	
	private HashMap<String, PacketTransportPluginFactory> packetTransportFactoryMap = new HashMap<String, PacketTransportPluginFactory> ();
	private HashMap<String, StreamTransportPluginFactory> streamTransportFactoryMap = new HashMap<String, StreamTransportPluginFactory> ();
	
	/**
	 * On adding a new transport plugin they are enabled by default.
	 * This can be used to disable plugins but without unloading them.
	 * Basically someone might design a plugin that provides many transports in one bundle.
	 * We can control each transport here.
	 */
	public HashMap<String, Boolean> enabledTransports;
	
	
	public TransportManager(Node node, TransportManagerConfig transportManagerConfig){
		this.node = node;
		this.transportManagerConfig = transportManagerConfig;
		this.transportMode = transportManagerConfig.transportMode;
		this.enabledTransports = transportManagerConfig.enabledTransports;
	}
	
	/**
	 * A plugin must register here and wait to be initialised.
	 * This method is called by RegisteredTransportManager
	 * @param transportPluginFactory
	 * @throws FaultyTransportPluginException The plugin must handle it if the mode is enabled, else a callback method is called later on.
	 */
	protected synchronized void register(PacketTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException{
		if(enabledTransports.containsKey(transportPluginFactory.getTransportName())) {
			if(enabledTransports.get(transportPluginFactory.getTransportName())) {	
				try{
					if(transportMode == TransportMode.opennet) {
						if(node.opennet != null){
							PacketTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
							packetTransportMap.put(transportPlugin.transportName, transportPlugin);
							node.opennet.crypto.handleNewTransport(transportPlugin);
						}
					}
					else if(transportMode == TransportMode.darknet){
						PacketTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
						packetTransportMap.put(transportPlugin.transportName, transportPlugin);
						node.darknetCrypto.handleNewTransport(transportPlugin);
					}
					packetTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
				} catch(MalformedPluginAddressException e) {
					Logger.error(this, "We have a wrong bind address", e);
				} catch(TransportInitException e) {
					Logger.error(this, "We already have an instance running or some other transport is using the port.", e);
				} catch (TransportPluginConfigurationException e) {
					//Store it but disable the transport since we don't have any configuration yet.
					packetTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
					enabledTransports.put(transportPluginFactory.getTransportName(), false);
				}
			}
			else
				packetTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
		}
		else{
			enabledTransports.put(transportPluginFactory.getTransportName(), true);
			register(transportPluginFactory);
		}
	}
	
	/**
	 * A plugin must register here and wait to be initialised.
	 * This method is called by RegisteredTransportManager
	 * @param transportPluginFactory
	 * @throws FaultyTransportPluginException The plugin must handle it if the mode is enabled, else a callback method is called later on.
	 */
	protected synchronized void register(StreamTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException{
		if(enabledTransports.containsKey(transportPluginFactory.getTransportName())) {
			if(enabledTransports.get(transportPluginFactory.getTransportName())) {	
				try{
					if(transportMode == TransportMode.opennet) {
						if(node.opennet != null){
							StreamTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
							streamTransportMap.put(transportPlugin.transportName, transportPlugin);
							node.opennet.crypto.handleNewTransport(transportPlugin);
						}
					}
					else if(transportMode == TransportMode.darknet){
						StreamTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
						streamTransportMap.put(transportPlugin.transportName, transportPlugin);
						node.darknetCrypto.handleNewTransport(transportPlugin);
					}
					streamTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
				} catch(MalformedPluginAddressException e) {
					Logger.error(this, "We have a wrong bind address", e);
				} catch(TransportInitException e) {
					Logger.error(this, "We already have an instance running or some other transport is using the port.", e);
				} catch (TransportPluginConfigurationException e) {
					//Store it but disable the transport since we don't have any configuration yet.
					streamTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
					enabledTransports.put(transportPluginFactory.getTransportName(), false);
				}
			}
			else
				streamTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
		}
		else{
			enabledTransports.put(transportPluginFactory.getTransportName(), true);
			register(transportPluginFactory);
		}
	}
	
	/**
	 * Create a transportPlugin instance from a factory object
	 * @return The plugin instance
	 * @throws FaultyTransportPluginException
	 * @throws UnknownHostException 
	 * @throws TransportInitException 
	 * @throws TransportPluginConfigurationException 
	 */
	private synchronized PacketTransportPlugin createTransportPlugin(PacketTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException, MalformedPluginAddressException, TransportInitException, TransportPluginConfigurationException {
		
		String factoryTransportName = transportPluginFactory.getTransportName();
		TransportConfig config = transportPluginFactory.toTransportConfig(transportManagerConfig.getTransportConfig(factoryTransportName));
		PacketTransportPlugin transportPlugin = transportPluginFactory.makeTransportPlugin(transportMode, config, node.collector, node.startupTime);
		
		if(transportPlugin == null)
			throw new FaultyTransportPluginException("Returned null plugin instance");
		
		if(factoryTransportName != transportPlugin.transportName)
			throw new FaultyTransportPluginException("Transport factory instance and transport instance do no have same transport name");
		
		//Check for valid mode of operation
		if(transportMode != transportPlugin.transportMode)
			throw new FaultyTransportPluginException("Wrong mode of operation");
		//Check for a valid transport name
		if( (transportPlugin.transportName == null) || (transportPlugin.transportName.length() < 1) )
			throw new FaultyTransportPluginException("Transport name can't be null");
		//Check if socketMap already has the same transport loaded.
		if(containsTransport(transportPlugin.transportName))
			throw new FaultyTransportPluginException("A transport type by the name of " + transportPlugin.transportName + " already exists!");
		
		return transportPlugin;
	}
	
	/**
	 * Create a transportPlugin instance from a factory object
	 * @return The plugin instance
	 * @throws FaultyTransportPluginException
	 * @throws UnknownHostException 
	 * @throws TransportInitException 
	 * @throws TransportPluginConfigurationException 
	 */
	private synchronized StreamTransportPlugin createTransportPlugin(StreamTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException, MalformedPluginAddressException, TransportInitException, TransportPluginConfigurationException {

		String factoryTransportName = transportPluginFactory.getTransportName();
		TransportConfig config = transportPluginFactory.toTransportConfig(transportManagerConfig.getTransportConfig(factoryTransportName));
		StreamTransportPlugin transportPlugin = transportPluginFactory.makeTransportPlugin(transportMode, config, node.collector, node.startupTime);;
		
		if(transportPlugin == null)
			throw new FaultyTransportPluginException("Returned null plugin instance");
		
		if(factoryTransportName != transportPlugin.transportName)
			throw new FaultyTransportPluginException("Transport factory instance and transport instance do no have same transport name");
		
		//Check for valid mode of operation
		if(transportMode != transportPlugin.transportMode)
			throw new FaultyTransportPluginException("Wrong mode of operation");
		//Check for a valid transport name
		if( (transportPlugin.transportName == null) || (transportPlugin.transportName.length() < 1) )
			throw new FaultyTransportPluginException("Transport name can't be null");
		//Check if socketMap already has the same transport loaded.
		if(containsTransport(transportPlugin.transportName))
			throw new FaultyTransportPluginException("A transport type by the name of " + transportPlugin.transportName + " already exists!");
		
		return transportPlugin;
	}
	
	/**
	 * When a plugin is being unloaded the manager must be notified. The plugin need not do this.
	 * @param transportName
	 * @throws TransportPluginException
	 */
	public synchronized void removeTransportPlugin(String transportName) throws TransportPluginException {
		if(containsTransportFactory(transportName)){
			disableTransport(transportName);
			if(packetTransportFactoryMap.containsKey(transportName))
				packetTransportFactoryMap.remove(transportName);
			else if(streamTransportFactoryMap.containsKey(transportName))
				streamTransportFactoryMap.remove(transportName);
			enabledTransports.remove(transportName);
		}
		else
			throw new TransportPluginException("Could not find the plugin");
	}
	
	/**
	 * Disable a transport. Plugin remains loaded, but a user wants to disable for a particular mode/transport
	 * @param transportName
	 * @throws TransportPluginException
	 */
	public synchronized void disableTransport(String transportName) throws TransportPluginException {
		if(containsTransport(transportName)){
			if(packetTransportMap.containsKey(transportName)){
				PacketTransportPlugin transportPlugin = packetTransportMap.get(transportName);
				transportPlugin.stopPlugin();
				packetTransportMap.remove(transportName);
			}
			else if(streamTransportMap.containsKey(transportName)){
				StreamTransportPlugin transportPlugin = streamTransportMap.get(transportName);
				transportPlugin.stopPlugin();
				streamTransportMap.remove(transportName);
			}
			enabledTransports.put(transportName, false);
			if(transportMode == TransportMode.opennet) {
				if(node.opennet != null){
					node.opennet.crypto.disableTransport(transportName);
				}
			}
			else if(transportMode == TransportMode.darknet){
				node.darknetCrypto.disableTransport(transportName);
			}
		}
		else 
			throw new TransportPluginException("Could not find the plugin");
	}
	
	
	//Do not change to public.
	synchronized HashMap<String, PacketTransportPlugin> initialiseNewPacketTransportMap() {
		for(String transportName : packetTransportFactoryMap.keySet()) {
			if(transportName == Node.defaultPacketTransportName)
				continue;
			if(!enabledTransports.get(transportName))
				continue;
			try {
				initialiseNewPacketTransportPlugin(transportName);
			} catch(TransportPluginException e) {
				//Logically not possible case.
			} catch (MalformedPluginAddressException e) {
				Logger.error(this, "We have a wrong bind address", e);
				continue; //Our fault
			} catch (TransportInitException e) {
				Logger.error(this, "We already have an instance running or some other transport is using the port.", e);
				continue;
			}
		}
		return packetTransportMap;
	}
	
	synchronized PacketTransportPlugin initialiseNewPacketTransportPlugin(String transportName) throws TransportPluginException, MalformedPluginAddressException, TransportInitException {
		if(!packetTransportFactoryMap.containsKey(transportName))
			throw new TransportPluginException("Transport not found");
		if(enabledTransports.get(transportName) == false)
			enabledTransports.put(transportName, true);
		PacketTransportPluginFactory transportFactory = packetTransportFactoryMap.get(transportName);
		PacketTransportPlugin transportPlugin;
		try {
			transportPlugin = createTransportPlugin(transportFactory);
			packetTransportMap.put(transportPlugin.transportName, transportPlugin);
		} catch(FaultyTransportPluginException e) {
			packetTransportFactoryMap.remove(transportName);
			transportFactory.invalidTransportCallback(e);
			return null;
		} catch (TransportPluginConfigurationException e) {
			Logger.error(this, "TransportManagerConfig does not have config", e);
			return null;
		}
		return transportPlugin;
	}
	
	public synchronized HashMap<String, PacketTransportPlugin> getPacketTransportMap(){
		return packetTransportMap;
	}
	
	synchronized HashMap<String, StreamTransportPlugin> initialiseNewStreamTransportMap() {
		for(String transportName : streamTransportFactoryMap.keySet()) {
			if(transportName == Node.defaultStreamTransportName)
				continue;
			if(enabledTransports.get(transportName) == false)
				continue;
			try {
				initialiseNewStreamTransportPlugin(transportName);
			} catch(TransportPluginException e) {
				//Logically not possible case.
			} catch (MalformedPluginAddressException e) {
				Logger.error(this, "We have a wrong bind address", e);
				continue; //Our fault
			} catch (TransportInitException e) {
				Logger.error(this, "We already have an instance running or some other transport is using the port.", e);
				continue;
			}
		}
		return streamTransportMap;
	}
	
	synchronized StreamTransportPlugin initialiseNewStreamTransportPlugin(String transportName) throws TransportPluginException, MalformedPluginAddressException, TransportInitException {
		if(!streamTransportFactoryMap.containsKey(transportName))
			throw new TransportPluginException("Transport not found");
		if(enabledTransports.get(transportName) == false)
			enabledTransports.put(transportName, true);
		StreamTransportPluginFactory transportFactory = streamTransportFactoryMap.get(transportName);
		StreamTransportPlugin transportPlugin;
		try {
			transportPlugin = createTransportPlugin(transportFactory);
			streamTransportMap.put(transportPlugin.transportName, transportPlugin);
		} catch(FaultyTransportPluginException e) {
			packetTransportFactoryMap.remove(transportName);
			transportFactory.invalidTransportCallback(e);
			return null;
		} catch (TransportPluginConfigurationException e) {
			Logger.error(this, "TransportManagerConfig does not have config", e);
			return null;
		}
		return transportPlugin;
	}
	
	public synchronized HashMap<String, StreamTransportPlugin> getStreamTransportMap(){
		return streamTransportMap;
	}
	
	/**
	 * This is for transports that are loaded by default. The code is present in the core of fred. For e.g. existing UDPSocketHandler
	 * The advantage is that freenet can load faster for normal users who don't want to use plugins
	 * The visibility of this method is default, package-locale access. We won't allow other classes to add here.
	 * The default plugin is added at the beginning. For UDPSocketHandler it is created in the NodeCrypto object.
	 * @param transportPlugin
	 * @throws FaultyTransportPluginException 
	 * @throws TransportPluginConfigurationException 
	 * @throws TransportInitException 
	 * @throws MalformedPluginAddressException 
	 */
	synchronized PacketTransportPlugin registerDefaultTransport(PacketTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException, MalformedPluginAddressException, TransportInitException, TransportPluginConfigurationException{
		if(enabledTransports.containsKey(transportPluginFactory.getTransportName())) {
			PacketTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
			packetTransportMap.put(transportPlugin.transportName, transportPlugin);
			packetTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
			return transportPlugin;
		}
		else {
			enabledTransports.put(transportPluginFactory.getTransportName(), true);
			return registerDefaultTransport(transportPluginFactory);
		}
	}
	/**
	 * This is for transports that are loaded by default. The code is present in the core of fred. For e.g. existing UDPSockethandler
	 * The advantage is that freenet can load faster for normal users who don't want to use plugins
	 * The visibility of this method is default, package-locale access. We won't allow other classes to add here.
	 * We still don't have a TCP default plugin. But we will need one.
	 * The default plugin is added at the beginning.
	 * @param transportPlugin
	 * @throws FaultyTransportPluginException 
	 * @throws TransportPluginConfigurationException 
	 * @throws TransportInitException 
	 * @throws MalformedPluginAddressException 
	 */
	synchronized StreamTransportPlugin registerDefaultTransport(StreamTransportPluginFactory transportPluginFactory) throws FaultyTransportPluginException, MalformedPluginAddressException, TransportInitException, TransportPluginConfigurationException{
		if(enabledTransports.containsKey(transportPluginFactory.getTransportName())) {
			StreamTransportPlugin transportPlugin = createTransportPlugin(transportPluginFactory);
			streamTransportMap.put(transportPlugin.transportName, transportPlugin);
			streamTransportFactoryMap.put(transportPluginFactory.getTransportName(), transportPluginFactory);
			if(transportMode == TransportMode.opennet)
				node.opennet.crypto.handleNewTransport(transportPlugin);
			else if(transportMode == TransportMode.darknet)
				node.darknetCrypto.handleNewTransport(transportPlugin);
			return transportPlugin;
		}
		else {
			enabledTransports.put(transportPluginFactory.getTransportName(), true);
			return registerDefaultTransport(transportPluginFactory);
		}
	}
	
	public synchronized boolean containsTransport(String transportName){
		if(packetTransportMap.containsKey(transportName))
			return true;
		else if(streamTransportMap.containsKey(transportName))
			return true;
		
		return false;
	}
	
	public synchronized boolean containsTransportFactory(String transportName){
		if(packetTransportFactoryMap.containsKey(transportName))
			return true;
		else if(streamTransportFactoryMap.containsKey(transportName))
			return true;
		
		return false;
	}
	
	public TransportManagerConfig getTransportManagerConfig(){
		return transportManagerConfig;
	}
}
