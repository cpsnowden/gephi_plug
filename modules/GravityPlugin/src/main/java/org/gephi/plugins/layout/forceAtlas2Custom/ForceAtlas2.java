/*
 Original source by code from Gephi
 Modifications made by Surya Rastogi
 Modified for Individual Project for completion of degree in BEng Computing 
*/

/*
 Copyright 2008-2011 Gephi
 Authors : Mathieu Jacomy <mathieu.jacomy@gmail.com>
 Website : http://www.gephi.org

 This file is part of Gephi.

 DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.

 Copyright 2011 Gephi Consortium. All rights reserved.

 The contents of this file are subject to the terms of either the GNU
 General Public License Version 3 only ("GPL") or the Common
 Development and Distribution License("CDDL") (collectively, the
 "License"). You may not use this file except in compliance with the
 License. You can obtain a copy of the License at
 http://gephi.org/about/legal/license-notice/
 or /cddl-1.0.txt and /gpl-3.0.txt. See the License for the
 specific language governing permissions and limitations under the
 License.  When distributing the software, include this License Header
 Notice in each file and include the License files at
 /cddl-1.0.txt and /gpl-3.0.txt. If applicable, add the following below the
 License Header, with the fields enclosed by brackets [] replaced by
 your own identifying information:
 "Portions Copyrighted [year] [name of copyright owner]"

 If you wish your version of this file to be governed by only the CDDL
 or only the GPL Version 3, indicate your decision by adding
 "[Contributor] elects to include this software in this distribution
 under the [CDDL or GPL Version 3] license." If you do not indicate a
 single choice of license, a recipient has the option to distribute
 your version of this file under either the CDDL, the GPL Version 3 or
 to extend the choice of license to its licensees as provided above.
 However, if you add GPL Version 3 code and therefore, elected the GPL
 Version 3 license, then the option applies only if the new code is
 made subject to such option by the copyright holder.

 Contributor(s):

 Portions Copyrighted 2011 Gephi Consortium.
 */
package org.gephi.plugins.layout.forceAtlas2Custom;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.gephi.plugins.layout.forceAtlas2Custom.ForceFactory.AttractionForce;
import org.gephi.plugins.layout.forceAtlas2Custom.ForceFactory.RepulsionForce;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
import java.io.*;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle;

/**
 * ForceAtlas 2 Layout, manages each step of the computations.
 *
 * @author Mathieu Jacomy
 */
public class ForceAtlas2 implements Layout {
    

    private GraphModel graphModel;
    private Graph graph;
    private final ForceAtlas2Builder layoutBuilder;
    private double edgeWeightInfluence;
    private double jitterTolerance;
    private double scalingRatio;
    private double gravity;
    private double speed;
    private double speedEfficiency;
    private boolean outboundAttractionDistribution;
    private boolean adjustSizes;
    private boolean barnesHutOptimize;
    private double barnesHutTheta;
    private boolean linLogMode;
    private boolean strongGravityMode;
    private int threadCount;
    private int currentThreadCount;
    private Region rootRegion;
    double outboundAttCompensation = 1;
    private ExecutorService pool;
    private static final double MAX_GRAVITY = 100;
    private double gravityXRatio;
    private double gravityYRatio;
    private List<Double> swingingHistory = new ArrayList();
    private List<Double> tractionHistory = new ArrayList();
    private boolean performanceMonitor = true;
    private List<Long> nanoStepTimes = new ArrayList<Long>();



    public ForceAtlas2(ForceAtlas2Builder layoutBuilder) {
        this.layoutBuilder = layoutBuilder;
        this.threadCount = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    @Override
    public void initAlgo() {        
        speed = 1.;
        speedEfficiency = 1.;

        graph = graphModel.getGraphVisible();

        graph.readLock();
        Node[] nodes = graph.getNodes().toArray();

        // Initialise layout data
        // Gravity sources adjustment parameters
        double min_x = Double.POSITIVE_INFINITY;
        double max_x = 0;
        double min_y = Double.POSITIVE_INFINITY;
        double max_y = 0;
        int missing = 0;
        int strength_missing = 0;
        for (Node n : nodes) {
            if (n.getLayoutData() == null || !(n.getLayoutData() instanceof ForceAtlas2LayoutData)) {
                ForceAtlas2LayoutData nLayout = new ForceAtlas2LayoutData();
                n.setLayoutData(nLayout);
            }

            double gravity_x_strength = 1.0;
            double gravity_y_strength = 1.0;

            try {
                gravity_x_strength = (Double) n.getAttribute("gravity_x_strength");
            }
            catch (IllegalArgumentException  ex){
                strength_missing++;
            }
            catch (NullPointerException ex){
                strength_missing++;
            }

            try {
                gravity_y_strength = (Double) n.getAttribute("gravity_y_strength");;
            }
            catch (IllegalArgumentException ex){
                strength_missing++;
            }
            catch (NullPointerException ex){
                strength_missing++;
            }
//            System.out.println(gravity_x_strength);
            // Calculating data for multiple sources of gravity
            double gravity_y = 0.0;
            double gravity_x = 0.0;
            // Try to access gravity_x and gravity_y attributes
            try {
                gravity_x = (Double) n.getAttribute("gravity_x");
            }
            catch (IllegalArgumentException  ex){
                missing++;
            }
            catch (NullPointerException ex){
                missing++;
            }
            
            try {
                gravity_y = (Double) n.getAttribute("gravity_y");;
            }
            catch (IllegalArgumentException ex){
                missing++;
            }
            catch (NullPointerException ex){
                missing++;
            }
            

            // Calculate gravity range to scale down if needed
            if (gravity_x < min_x) min_x = gravity_x;
            if (gravity_x > max_x) max_x = gravity_x;           
            if (gravity_y < min_y) min_y = gravity_y;            
            if (gravity_y > max_y) max_y = gravity_y;
            
            ForceAtlas2LayoutData nLayout = n.getLayoutData();
            nLayout.mass = 1 + graph.getDegree(n);
            nLayout.old_dx = 0;
            nLayout.old_dy = 0;
            nLayout.dx = 0;
            nLayout.dy = 0;

            nLayout.gravity_x_strength = gravity_x_strength;
            nLayout.gravity_y_strength = gravity_y_strength;
        }

        if (strength_missing > 0) {
            System.out.println("Some nodes missing attributes(" + strength_missing + ") 'gravity_x_strength' or 'gravity_y_strength', using 1.0 instead");
        }
        
        // Scaling down gravity_x
        double range_x = max_x - min_x;
        double scale_x = 1;
        if (range_x > MAX_GRAVITY) {
            scale_x = range_x/MAX_GRAVITY;
        }
        // Scaling down gravity_y
        double range_y = max_y - min_y;
        double scale_y = 1;
        if (range_y > MAX_GRAVITY) {
            scale_y = range_y/MAX_GRAVITY;
        }
        
        // Setting Layout data for gravity sources
        for(Node n : nodes){
            double gravity_y = 0.0;
            double gravity_x = 0.0;
            
            try {
                gravity_x = (Double) n.getAttribute("gravity_x");
            }
            catch (IllegalArgumentException ex){
            }
            catch (NullPointerException ex){  
            }
            
            try {
                gravity_y = (Double) n.getAttribute("gravity_y");;
            }
            catch (IllegalArgumentException ex){
            }
            catch (NullPointerException ex){  
            }

            ForceAtlas2LayoutData nLayout = n.getLayoutData();
            //Center middle source of gravity
            nLayout.gravity_x = ((((gravity_x - min_x) - range_x/2)/scale_x));
            nLayout.gravity_y = ((((gravity_y - min_y) - range_y/2)/scale_y));
        }
        
        if (missing > 0) {
            System.out.println("Some nodes missing attributes(" + missing + ") 'gravity_x' or 'gravity_y', using 0.0 instead");
        }
        System.out.println("Blah");
        pool = Executors.newFixedThreadPool(threadCount);
        currentThreadCount = threadCount;
    }

    @Override
    public void goAlgo() {
        // Initialize graph data
        if (graphModel == null) {
            return;
        }
        long start = System.nanoTime();
        System.out.println("Running");
        graph = graphModel.getGraphVisible();

        graph.readLock();
        Node[] nodes = graph.getNodes().toArray();
        Edge[] edges = graph.getEdges().toArray();

        // Initialise layout data
        for (Node n : nodes) {
            if (n.getLayoutData() == null || !(n.getLayoutData() instanceof ForceAtlas2LayoutData)) {
                ForceAtlas2LayoutData nLayout = new ForceAtlas2LayoutData();
                n.setLayoutData(nLayout);
            }
            ForceAtlas2LayoutData nLayout = n.getLayoutData();
            nLayout.mass = 1 + graph.getDegree(n);
            nLayout.old_dx = nLayout.dx;
            nLayout.old_dy = nLayout.dy;
            nLayout.dx = 0;
            nLayout.dy = 0;
        }

        // If Barnes Hut active, initialize root region
        if (isBarnesHutOptimize()) {
            rootRegion = new Region(nodes);
            rootRegion.buildSubRegions();
        }

        // If outboundAttractionDistribution active, compensate.
        if (isOutboundAttractionDistribution()) {
            outboundAttCompensation = 0;
            for (Node n : nodes) {
                ForceAtlas2LayoutData nLayout = n.getLayoutData();
                outboundAttCompensation += nLayout.mass;
            }
            outboundAttCompensation /= nodes.length;
        }

        // Repulsion (and gravity)
        // NB: Muti-threaded
        RepulsionForce Repulsion = ForceFactory.builder.buildRepulsion(isAdjustSizes(), getScalingRatio());

        int taskCount = 8 * currentThreadCount;  // The threadPool Executor Service will manage the fetching of tasks and threads.
        // We make more tasks than threads because some tasks may need more time to compute.
        ArrayList<Future> threads = new ArrayList();
        for (int t = taskCount; t > 0; t--) {
            int from = (int) Math.floor(nodes.length * (t - 1) / taskCount);
            int to = (int) Math.floor(nodes.length * t / taskCount);
            Future future = pool.submit(new NodesThread(nodes, from, to, isBarnesHutOptimize(), getBarnesHutTheta(), getGravity(), (isStrongGravityMode()) ? (ForceFactory.builder.getStrongGravity(getScalingRatio(), gravityXRatio, gravityYRatio)) : (Repulsion), getScalingRatio(), rootRegion, Repulsion));
            threads.add(future);
        }
        for (Future future : threads) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Exceptions.printStackTrace(ex);
            } catch (ExecutionException ex) {
                Exceptions.printStackTrace(ex);
            }
        }

        // Attraction
        AttractionForce Attraction = ForceFactory.builder.buildAttraction(isLinLogMode(), isOutboundAttractionDistribution(), isAdjustSizes(), 1 * ((isOutboundAttractionDistribution()) ? (outboundAttCompensation) : (1)));
        if (getEdgeWeightInfluence() == 0) {
            for (Edge e : edges) {
                Attraction.apply(e.getSource(), e.getTarget(), 1);
            }
        } else if (getEdgeWeightInfluence() == 1) {
            for (Edge e : edges) {
                Attraction.apply(e.getSource(), e.getTarget(), e.getWeight());
            }
        } else {
            for (Edge e : edges) {
                Attraction.apply(e.getSource(), e.getTarget(), Math.pow(e.getWeight(), getEdgeWeightInfluence()));
            }
        }

        // Auto adjust speed
        double totalSwinging = 0d;  // How much irregular movement
        double totalEffectiveTraction = 0d;  // Hom much useful movement
        for (Node n : nodes) {
            ForceAtlas2LayoutData nLayout = n.getLayoutData();
            if (!n.isFixed()) {
                double swinging = Math.sqrt(Math.pow(nLayout.old_dx - nLayout.dx, 2) + Math.pow(nLayout.old_dy - nLayout.dy, 2));
                totalSwinging += nLayout.mass * swinging;   // If the node has a burst change of direction, then it's not converging.
                totalEffectiveTraction += nLayout.mass * 0.5 * Math.sqrt(Math.pow(nLayout.old_dx + nLayout.dx, 2) + Math.pow(nLayout.old_dy + nLayout.dy, 2));
            }
        }
//        swingingHistory.add(totalSwinging);
//        tractionHistory.add(totalEffectiveTraction);
//        System.out.println(totalSwinging + ","+totalEffectiveTraction);
        // We want that swingingMovement < tolerance * convergenceMovement

        // Optimize jitter tolerance
        // The 'right' jitter tolerance for this network. Bigger networks need more tolerance. Denser networks need less tolerance. Totally empiric.
        double estimatedOptimalJitterTolerance = 0.05 * Math.sqrt(nodes.length);
        double minJT = Math.sqrt(estimatedOptimalJitterTolerance);
        double maxJT = 10;
        double jt = jitterTolerance * Math.max(minJT, Math.min(maxJT, estimatedOptimalJitterTolerance * totalEffectiveTraction / Math.pow(nodes.length, 2)));

        double minSpeedEfficiency = 0.05;

        // Protection against erratic behavior
        if (totalSwinging / totalEffectiveTraction > 2.0) {
            if (speedEfficiency > minSpeedEfficiency) {
                speedEfficiency *= 0.5;
            }
            jt = Math.max(jt, jitterTolerance);
        }

        double targetSpeed = jt * speedEfficiency * totalEffectiveTraction / totalSwinging;

        // Speed efficiency is how the speed really corresponds to the swinging vs. convergence tradeoff
        // We adjust it slowly and carefully
        if (totalSwinging > jt * totalEffectiveTraction) {
            if (speedEfficiency > minSpeedEfficiency) {
                speedEfficiency *= 0.7;
            }
        } else if (speed < 1000) {
            speedEfficiency *= 1.3;
        }

        // But the speed shoudn't rise too much too quickly, since it would make the convergence drop dramatically.
        double maxRise = 0.5;   // Max rise: 50%
        speed = speed + Math.min(targetSpeed - speed, maxRise * speed);

        // Apply forces
        if (isAdjustSizes()) {
            // If nodes overlap prevention is active, it's not possible to trust the swinging mesure.
            for (Node n : nodes) {
                ForceAtlas2LayoutData nLayout = n.getLayoutData();
                if (!n.isFixed()) {

                    // Adaptive auto-speed: the speed of each node is lowered
                    // when the node swings.
                    double swinging = nLayout.mass * Math.sqrt((nLayout.old_dx - nLayout.dx) * (nLayout.old_dx - nLayout.dx) + (nLayout.old_dy - nLayout.dy) * (nLayout.old_dy - nLayout.dy));
                    double factor = 0.1 * speed / (1f + Math.sqrt(speed * swinging));

                    double df = Math.sqrt(Math.pow(nLayout.dx, 2) + Math.pow(nLayout.dy, 2));
                    factor = Math.min(factor * df, 10.) / df;

                    double x = n.x() + nLayout.dx * factor;
                    double y = n.y() + nLayout.dy * factor;

                    n.setX((float) x);
                    n.setY((float) y);
                }
            }
        } else {
            for (Node n : nodes) {
                ForceAtlas2LayoutData nLayout = n.getLayoutData();
                if (!n.isFixed()) {

                    // Adaptive auto-speed: the speed of each node is lowered
                    // when the node swings.
                    double swinging = nLayout.mass * Math.sqrt((nLayout.old_dx - nLayout.dx) * (nLayout.old_dx - nLayout.dx) + (nLayout.old_dy - nLayout.dy) * (nLayout.old_dy - nLayout.dy));
                    //double factor = speed / (1f + Math.sqrt(speed * swinging));
                    double factor = speed / (1f + Math.sqrt(speed * swinging));

                    double x = n.x() + nLayout.dx * factor;
                    double y = n.y() + nLayout.dy * factor;

                    n.setX((float) x);
                    n.setY((float) y);
                }
            }
        }
        graph.readUnlockAll();
        long step = System.nanoTime() - start;
        System.out.println("Time: " + step + " " + nanoStepTimes.size());
        nanoStepTimes.add(step);
    }

    @Override
    public boolean canAlgo() {
        return graphModel != null;
    }

    @Override
    public void endAlgo() {
//        System.out.println("Writing data file");
//        try {
//            File file = new File("./Storage.dat");
//            if (!file.exists()) {
//                file.createNewFile();
//            }
//            FileWriter fw = new FileWriter(file);
//            BufferedWriter bw = new BufferedWriter(fw);
//            bw.write("Traction,Swing\n");
//            for (int i = 0; i < tractionHistory.size(); i++) {
//                bw.write(tractionHistory.get(i).toString() + "," + swingingHistory.get(i).toString() + "\n");
//            }
//            bw.flush();
//            bw.close();
//        }catch(IOException e){
//
//        }
        try {
            FileWriter writer = new FileWriter("Performance" + System.nanoTime() + ".dat");
            writer.write("[");
            for(Long l:nanoStepTimes){
                writer.write(l + ",");
            }
            writer.write("]");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (Node n : graph.getNodes()) {
            n.setLayoutData(null);
        }
        pool.shutdown();
        graph.readUnlockAll();
    }

    @Override
    public LayoutProperty[] getProperties() {
        List<LayoutProperty> properties = new ArrayList<LayoutProperty>();
        final String FORCEATLAS2_TUNING = NbBundle.getMessage(getClass(), "ForceAtlas2.tuning");
        final String FORCEATLAS2_BEHAVIOR = NbBundle.getMessage(getClass(), "ForceAtlas2.behavior");
        final String FORCEATLAS2_PERFORMANCE = NbBundle.getMessage(getClass(), "ForceAtlas2.performance");
        final String FORCEATLAS2_THREADS = NbBundle.getMessage(getClass(), "ForceAtlas2.threads");

        try {
            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.scalingRatio.name"),
                    FORCEATLAS2_TUNING,
                    "ForceAtlas2.scalingRatio.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.scalingRatio.desc"),
                    "getScalingRatio", "setScalingRatio"));
            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravityXRatio.name"),
                    FORCEATLAS2_TUNING,
                    "ForceAtlas2.gravityXRatio.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravityXRatio.desc"),
                    "getGravityXRatio", "setGravityXRatio"));
            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravityYRatio.name"),
                    FORCEATLAS2_TUNING,
                    "ForceAtlas2.gravityYRatio.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravityYRatio.desc"),
                    "getGravityYRatio", "setGravityYRatio"));
            
            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.strongGravityMode.name"),
                    FORCEATLAS2_TUNING,
                    "ForceAtlas2.strongGravityMode.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.strongGravityMode.desc"),
                    "isStrongGravityMode", "setStrongGravityMode"));

            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravity.name"),
                    FORCEATLAS2_TUNING,
                    "ForceAtlas2.gravity.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.gravity.desc"),
                    "getGravity", "setGravity"));

            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.distributedAttraction.name"),
                    FORCEATLAS2_BEHAVIOR,
                    "ForceAtlas2.distributedAttraction.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.distributedAttraction.desc"),
                    "isOutboundAttractionDistribution", "setOutboundAttractionDistribution"));

            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.linLogMode.name"),
                    FORCEATLAS2_BEHAVIOR,
                    "ForceAtlas2.linLogMode.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.linLogMode.desc"),
                    "isLinLogMode", "setLinLogMode"));

            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.adjustSizes.name"),
                    FORCEATLAS2_BEHAVIOR,
                    "ForceAtlas2.adjustSizes.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.adjustSizes.desc"),
                    "isAdjustSizes", "setAdjustSizes"));

            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.edgeWeightInfluence.name"),
                    FORCEATLAS2_BEHAVIOR,
                    "ForceAtlas2.edgeWeightInfluence.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.edgeWeightInfluence.desc"),
                    "getEdgeWeightInfluence", "setEdgeWeightInfluence"));

            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.jitterTolerance.name"),
                    FORCEATLAS2_PERFORMANCE,
                    "ForceAtlas2.jitterTolerance.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.jitterTolerance.desc"),
                    "getJitterTolerance", "setJitterTolerance"));

            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.barnesHutOptimization.name"),
                    FORCEATLAS2_PERFORMANCE,
                    "ForceAtlas2.barnesHutOptimization.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.barnesHutOptimization.desc"),
                    "isBarnesHutOptimize", "setBarnesHutOptimize"));

            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.barnesHutTheta.name"),
                    FORCEATLAS2_PERFORMANCE,
                    "ForceAtlas2.barnesHutTheta.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.barnesHutTheta.desc"),
                    "getBarnesHutTheta", "setBarnesHutTheta"));

            properties.add(LayoutProperty.createProperty(
                    this, Integer.class,
                    NbBundle.getMessage(getClass(), "ForceAtlas2.threads.name"),
                    FORCEATLAS2_THREADS,
                    "ForceAtlas2.threads.name",
                    NbBundle.getMessage(getClass(), "ForceAtlas2.threads.desc"),
                    "getThreadsCount", "setThreadsCount"));

        } catch (Exception e) {
            e.printStackTrace();
        }

        return properties.toArray(new LayoutProperty[0]);
    }

    @Override
    public void resetPropertiesValues() {
        int nodesCount = 0;

        if (graphModel != null) {
            nodesCount = graphModel.getGraphVisible().getNodeCount();
        }

        // Tuning
        if (nodesCount >= 100) {
            setScalingRatio(2.0);
        } else {
            setScalingRatio(10.0);
        }
        setStrongGravityMode(false);
        setGravity(1.);
        
        setGravityXRatio(2.5);
        setGravityYRatio(2.5);

        // Behavior
        setOutboundAttractionDistribution(false);
        setLinLogMode(false);
        setAdjustSizes(false);
        setEdgeWeightInfluence(1.);

        // Performance
        setJitterTolerance(1d);
        if (nodesCount >= 1000) {
            setBarnesHutOptimize(true);
        } else {
            setBarnesHutOptimize(false);
        }
        setBarnesHutTheta(1.2);
        setThreadsCount(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    @Override
    public LayoutBuilder getBuilder() {
        return layoutBuilder;
    }

    @Override
    public void setGraphModel(GraphModel graphModel) {
        this.graphModel = graphModel;
        // Trick: reset here to take the profile of the graph in account for default values
        resetPropertiesValues();
    }

    public Double getBarnesHutTheta() {
        return barnesHutTheta;
    }

    public void setBarnesHutTheta(Double barnesHutTheta) {
        this.barnesHutTheta = barnesHutTheta;
    }

    public Double getEdgeWeightInfluence() {
        return edgeWeightInfluence;
    }

    public void setEdgeWeightInfluence(Double edgeWeightInfluence) {
        this.edgeWeightInfluence = edgeWeightInfluence;
    }

    public Double getJitterTolerance() {
        return jitterTolerance;
    }

    public void setJitterTolerance(Double jitterTolerance) {
        this.jitterTolerance = jitterTolerance;
    }

    public Boolean isLinLogMode() {
        return linLogMode;
    }

    public void setLinLogMode(Boolean linLogMode) {
        this.linLogMode = linLogMode;
    }

    public Double getScalingRatio() {
        return scalingRatio;
    }

    public void setScalingRatio(Double scalingRatio) {
        this.scalingRatio = scalingRatio;
    }
    
    public Double getGravityXRatio() {
        return this.gravityXRatio;
    }

    public void setGravityXRatio(Double gravityXRatio) {
        this.gravityXRatio = gravityXRatio;
    }
    
    public Double getGravityYRatio() {
        return this.gravityYRatio;
    }

    public void setGravityYRatio(Double gravityYRatio) {
        this.gravityYRatio = gravityYRatio;
    }

    public Boolean isStrongGravityMode() {
        return strongGravityMode;
    }

    public void setStrongGravityMode(Boolean strongGravityMode) {
        this.strongGravityMode = strongGravityMode;
    }

    public Double getGravity() {
        return gravity;
    }

    public void setGravity(Double gravity) {
        this.gravity = gravity;
    }

    public Integer getThreadsCount() {
        return threadCount;
    }

    public void setThreadsCount(Integer threadCount) {
        this.threadCount = Math.max(1, threadCount);
    }

    public Boolean isOutboundAttractionDistribution() {
        return outboundAttractionDistribution;
    }

    public void setOutboundAttractionDistribution(Boolean outboundAttractionDistribution) {
        this.outboundAttractionDistribution = outboundAttractionDistribution;
    }

    public Boolean isAdjustSizes() {
        return adjustSizes;
    }

    public void setAdjustSizes(Boolean adjustSizes) {
        this.adjustSizes = adjustSizes;
    }

    public Boolean isBarnesHutOptimize() {
        return barnesHutOptimize;
    }

    public void setBarnesHutOptimize(Boolean barnesHutOptimize) {
        this.barnesHutOptimize = barnesHutOptimize;
    }
}
