/**
 * <p>Simulators and test code using the real Freenet code: Either multiple 
 * nodes in the same JVM to test some key feature (routing, ULPR propagation
 * etc), or one or more real nodes connect to the real network and do some 
 * tests e.g. bootstrapping, inserting and fetching data.</p>
 * 
 * <p>To run simulations, do something like:</p>
 * 
 * <p><pre>java -cp freenet.jar:freenet-ext.jar freenet.node.simulator.RealNodeProbeTest</pre></p>
 * 
 * <p>On Windows the classpath separator is ; instead of :.</p>
 */
package freenet.node.simulator;
