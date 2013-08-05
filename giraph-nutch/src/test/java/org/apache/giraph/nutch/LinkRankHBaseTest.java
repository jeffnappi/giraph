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

package org.apache.giraph.nutch;


import org.apache.giraph.BspCase;
import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.edge.ByteArrayEdges;
import org.apache.giraph.nutch.LinkRank.*;
import org.apache.giraph.job.GiraphJob;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Test case for LinkRank reading edges and vertex scores
 * from HBase, calculates new scores and updates the HBase
 * Table again.
 */
public class LinkRankHBaseTest extends BspCase {


  private final Logger LOG = Logger.getLogger(LinkRankHBaseTest.class);

  private static final String TABLE_NAME = "simple_graph";
  private static final double DELTA = 1e-3;

  private HBaseTestingUtility testUtil;
  private Path hbaseRootdir;


  public LinkRankHBaseTest() {
    super(LinkRankHBaseTest.class.getName());

    // Let's set up the hbase root directory.
    Configuration conf = HBaseConfiguration.create();
    try {
      FileSystem fs = FileSystem.get(conf);
      String randomStr = UUID.randomUUID().toString();
      String tmpdir = System.getProperty("java.io.tmpdir") + "/" +
          randomStr + "/";
      hbaseRootdir = fs.makeQualified(new Path(tmpdir));
      conf.set(HConstants.HBASE_DIR, hbaseRootdir.toString());
      fs.mkdirs(hbaseRootdir);
    } catch(IOException ioe) {
      fail("Could not create hbase root directory.");
    }

    // Start the test utility.
    testUtil = new HBaseTestingUtility(conf);
  }

  @Test
  public void testHBaseInputOutput() throws Exception {

    if (System.getProperty("prop.mapred.job.tracker") != null) {
      if(LOG.isInfoEnabled())
        LOG.info("testHBaseInputOutput: Ignore this test if not local mode.");
      return;
    }

    File jarTest = new File(System.getProperty("prop.jarLocation"));
    if(!jarTest.exists()) {
      fail("Could not find Giraph jar at " +
          "location specified by 'prop.jarLocation'. " +
          "Make sure you built the main Giraph artifact?.");
    }

    MiniHBaseCluster cluster = null;
    MiniZooKeeperCluster zkCluster = null;
    FileSystem fs = null;

    try {
      // using the restart method allows us to avoid having the hbase
      // root directory overwritten by /home/$username
      zkCluster = testUtil.startMiniZKCluster();
      testUtil.restartHBaseCluster(2);
      cluster = testUtil.getMiniHBaseCluster();

      final byte[] FAM_OL = Bytes.toBytes("ol");
      final byte[] FAM_S = Bytes.toBytes("s");
      final byte[] QUALIFIER_PAGERANK = Bytes.toBytes("linkrank");
      final byte[] TAB = Bytes.toBytes(TABLE_NAME);

      Configuration conf = cluster.getConfiguration();
      HTableDescriptor desc = new HTableDescriptor(TAB);
      desc.addFamily(new HColumnDescriptor(FAM_OL));
      desc.addFamily(new HColumnDescriptor(FAM_S));
      HBaseAdmin hbaseAdmin=new HBaseAdmin(conf);
      if (hbaseAdmin.isTableAvailable(TABLE_NAME)) {
        hbaseAdmin.disableTable(TABLE_NAME);
        hbaseAdmin.deleteTable(TABLE_NAME);
      }
      hbaseAdmin.createTable(desc);

      /**
       * Enter the initial data
       * (a,b), (b,c), (a,c)
       * a = 0.33 - google
       * b = 0.33 - yahoo
       * c = 0.33 - bing
       */

      HTable table = new HTable(conf, TABLE_NAME);
      Put p1 = new Put(Bytes.toBytes("com.google.www:http/"));
      //ol:b
      p1.add(Bytes.toBytes("ol"), Bytes.toBytes("http://www.yahoo.com/"), Bytes.toBytes("ab"));
      //s:S
      p1.add(Bytes.toBytes("s"), Bytes.toBytes("s"), Bytes.toBytes(0.33d));

      Put p2 = new Put(Bytes.toBytes("com.google.www:http/"));
      p2.add(Bytes.toBytes("ol"), Bytes.toBytes("http://www.bing.com/"), Bytes.toBytes("ac"));

      Put p3 = new Put(Bytes.toBytes("com.yahoo.www:http/"));
      p3.add(Bytes.toBytes("ol"), Bytes.toBytes("http://www.bing.com/"), Bytes.toBytes("bc"));
      p3.add(Bytes.toBytes("ol"), Bytes.toBytes("http://"), Bytes.toBytes("fake"));
      p3.add(Bytes.toBytes("s"), Bytes.toBytes("s"), Bytes.toBytes(0.33d));

      Put p4 = new Put(Bytes.toBytes("com.bing.www:http/"));
      p4.add(Bytes.toBytes("s"), Bytes.toBytes("s"), Bytes.toBytes(0.33d));
      p4.add(Bytes.toBytes("ol"), Bytes.toBytes("http://aefaef"), Bytes.toBytes("fake2"));

      Put p5 = new Put(Bytes.toBytes("afekomafke"));
      p5.add(Bytes.toBytes("s"), Bytes.toBytes("s"), Bytes.toBytes(10.0d));

      table.put(p1);
      table.put(p2);
      table.put(p3);
      table.put(p4);
      table.put(p5);


      // Set Giraph configuration
      //now operate over HBase using Vertex I/O formats
      conf.set(TableInputFormat.INPUT_TABLE, TABLE_NAME);
      conf.set(TableOutputFormat.OUTPUT_TABLE, TABLE_NAME);

      // Start the giraph job
      GiraphJob giraphJob = new GiraphJob(conf, BspCase.getCallingMethodName());
      GiraphConfiguration giraphConf = giraphJob.getConfiguration();
      giraphConf.setZooKeeperConfiguration(
          cluster.getMaster().getZooKeeper().getQuorum());
      setupConfiguration(giraphJob);
      giraphConf.setComputationClass(LinkRankComputation.class);
      giraphConf.setMasterComputeClass(LinkRankVertexMasterCompute.class);
      giraphConf.setWorkerContextClass(LinkRankVertexWorkerContext.class);
      giraphConf.setOutEdgesClass(ByteArrayEdges.class);
      giraphConf.setVertexInputFormatClass(Nutch2WebpageInputFormat.class);
      giraphConf.setVertexOutputFormatClass(Nutch2WebpageOutputFormat.class);
      giraphConf.setInt("giraph.linkRank.superstepCount", 10);
      giraphConf.setInt("giraph.linkRank.scale", 10);
      giraphConf.setVertexInputFilterClass(LinkRankVertexFilter.class);

      assertTrue(giraphJob.run(true));

      if(LOG.isInfoEnabled())
        LOG.info("Giraph job successful. Checking output qualifier.");

      /** Check the results **/

      Result result;
      String key;
      byte[] calculatedScoreByte;
      HashMap actualValues = new HashMap<String, Double>();
      actualValues.put("com.google.www:http/", 1.3515060339386287d);
      actualValues.put("com.yahoo.www:http/", 4.144902009567587d);
      actualValues.put("com.bing.www:http/", 9.063893290511482d);

      for (Object keyObject : actualValues.keySet()){
        key = keyObject.toString();
        result = table.get(new Get(key.getBytes()));
        calculatedScoreByte = result.getValue(FAM_S, QUALIFIER_PAGERANK);
        assertNotNull(calculatedScoreByte);
        assertTrue(calculatedScoreByte.length > 0);
        Assert.assertEquals("Scores are not the same", (Double)actualValues.get(key), Bytes.toDouble(calculatedScoreByte), DELTA);
      }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
      if (zkCluster != null) {
        zkCluster.shutdown();
      }
      // clean test files
      if (fs != null) {
        fs.delete(hbaseRootdir);
      }
    }
  }
}
