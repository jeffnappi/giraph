/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.giraph.examples;

import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.edge.ByteArrayEdges;
import org.apache.giraph.examples.LinkRank.*;
import org.apache.giraph.utils.InternalVertexRunner;
import org.junit.Test;

/**
 * Tests for {@link org.apache.giraph.examples.LinkRank.LinkRankVertex}
 */
public class LinkRankVertexTest {
    private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(LinkRankVertex.class);

    @Test
    public void testToyData() throws Exception {
        String[] graph = new String[] { "12 34 56", "34 78", "56 34 78", "78 34" };

        GiraphConfiguration conf = new GiraphConfiguration();
//        conf.setFloat(RandomWalkWithRestartVertex.TELEPORTATION_PROBABILITY, 0.25f);

        conf.setVertexClass(LinkRankVertex.class);
        conf.setOutEdgesClass(ByteArrayEdges.class);
        conf.setVertexInputFormatClass(LongDoubleDoubleTextInputFormat.class);
        conf.setVertexOutputFormatClass(
                VertexWithDoubleValueDoubleEdgeTextOutputFormat.class);
        //conf.setWorkerContextClass(RandomWalkWorkerContext.class);
        //conf.setMasterComputeClass(RandomWalkVertexMasterCompute.class);
        // Run internally
        Iterable<String> results = InternalVertexRunner.run(conf, graph);

        /*
        Map<Long, Double> steadyStateProbabilities =
                RandomWalkTestUtils.parseSteadyStateProbabilities(results);
        // values computed with external software
        // 0.25, 0.354872, 0.09375, 0.301377
        assertEquals(0.25, steadyStateProbabilities.get(12L), RandomWalkTestUtils.EPSILON);
        assertEquals(0.354872, steadyStateProbabilities.get(34L),
                RandomWalkTestUtils.EPSILON);
        assertEquals(0.09375, steadyStateProbabilities.get(56L), RandomWalkTestUtils.EPSILON);
        assertEquals(0.301377, steadyStateProbabilities.get(78L),
                RandomWalkTestUtils.EPSILON);*/


    }

    @Test
    public void testToyData2() throws Exception {

        // A small graph
        String[] graph = new String[] {
                "1 4 2 3",
                "2 1",
                "4 3 2",
                "5 2 4"
        };

        GiraphConfiguration conf = new GiraphConfiguration();
        conf.setVertexClass(LinkRankVertex.class);
        conf.setOutEdgesClass(ByteArrayEdges.class);

        conf.setVertexInputFormatClass(LinkRankVertexInputFormat.class);
        conf.setVertexOutputFormatClass(
                LinkRankVertexOutputFormat.class);
        conf.setWorkerContextClass(LinkRankVertexWorkerContext.class);
        conf.setMasterComputeClass(LinkRankVertexMasterCompute.class);
        // Run internally
        Iterable<String> results = InternalVertexRunner.run(conf, graph);
        for (String s : results){
            log.info(s);
            System.out.println("hebele:" + s);
        }


    }

}