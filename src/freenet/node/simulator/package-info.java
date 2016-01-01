/**
 * Simulators and test code using the real Freenet code: Either multiple 
 * nodes in the same JVM to test some key feature (routing, ULPR propagation
 * etc), or one or more real nodes connect to the real network and do some 
 * tests e.g. bootstrapping, inserting and fetching data.
 * 
 * To run simulations, do something like:
 * 
 * java -cp freenet.jar:freenet-ext.jar freenet.node.simulator.RealNodeProbeTest
 * 
 * On Windows the classpath separator is ; instead of :.
 */
package freenet.node.simulator;
