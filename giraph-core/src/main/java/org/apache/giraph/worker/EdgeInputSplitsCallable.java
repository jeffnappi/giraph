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

package org.apache.giraph.worker;

import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.VertexEdgeCount;
import org.apache.giraph.io.EdgeInputFormat;
import org.apache.giraph.io.EdgeReader;
import org.apache.giraph.io.filters.EdgeInputFilter;
import org.apache.giraph.utils.LoggerUtils;
import org.apache.giraph.utils.MemoryUtils;
import org.apache.giraph.zk.ZooKeeperExt;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Meter;

import java.io.IOException;

/**
 * Load as many edge input splits as possible.
 * Every thread will has its own instance of WorkerClientRequestProcessor
 * to send requests.
 *
 * @param <I> Vertex id
 * @param <V> Vertex value
 * @param <E> Edge value
 */
public class EdgeInputSplitsCallable<I extends WritableComparable,
    V extends Writable, E extends Writable>
    extends InputSplitsCallable<I, V, E> {
  /** How often to update metrics and print info */
  public static final int EDGES_UPDATE_PERIOD = 1000000;
  /** How often to update filtered metrics */
  public static final int EDGES_FILTERED_UPDATE_PERIOD = 10000;

  /** Class logger */
  private static final Logger LOG = Logger.getLogger(
      EdgeInputSplitsCallable.class);

  /** Aggregator handler */
  private final WorkerThreadAggregatorUsage aggregatorUsage;
  /** Edge input format */
  private final EdgeInputFormat<I, E> edgeInputFormat;
  /** Input split max edges (-1 denotes all) */
  private final long inputSplitMaxEdges;

  /** Filter to use */
  private final EdgeInputFilter<I, E> edgeInputFilter;

  // Metrics
  /** edges loaded meter across all readers */
  private final Meter totalEdgesMeter;
  /** edges filtered out by user */
  private final Counter totalEdgesFiltered;

  /**
   * Constructor.
   *
   * @param edgeInputFormat Edge input format
   * @param context Context
   * @param configuration Configuration
   * @param bspServiceWorker service worker
   * @param splitsHandler Handler for input splits
   * @param zooKeeperExt Handle to ZooKeeperExt
   */
  public EdgeInputSplitsCallable(
      EdgeInputFormat<I, E> edgeInputFormat,
      Mapper<?, ?, ?, ?>.Context context,
      ImmutableClassesGiraphConfiguration<I, V, E> configuration,
      BspServiceWorker<I, V, E> bspServiceWorker,
      InputSplitsHandler splitsHandler,
      ZooKeeperExt zooKeeperExt)  {
    super(context, configuration, bspServiceWorker, splitsHandler,
        zooKeeperExt);
    this.edgeInputFormat = edgeInputFormat;

    inputSplitMaxEdges = configuration.getInputSplitMaxEdges();
    // Initialize aggregator usage.
    this.aggregatorUsage = bspServiceWorker.getAggregatorHandler()
      .newThreadAggregatorUsage();
    edgeInputFilter = configuration.getEdgeInputFilter();

    // Initialize Metrics
    totalEdgesMeter = getTotalEdgesLoadedMeter();
    totalEdgesFiltered = getTotalEdgesFilteredCounter();
  }

  @Override
  public EdgeInputFormat<I, E> getInputFormat() {
    return edgeInputFormat;
  }

  /**
   * Read edges from input split.  If testing, the user may request a
   * maximum number of edges to be read from an input split.
   *
   * @param inputSplit Input split to process with edge reader
   * @return Edges loaded from this input split
   * @throws IOException
   * @throws InterruptedException
   */
  @Override
  protected VertexEdgeCount readInputSplit(
      InputSplit inputSplit) throws IOException,
      InterruptedException {
    EdgeReader<I, E> edgeReader =
        edgeInputFormat.createEdgeReader(inputSplit, context);
    edgeReader.setConf(
        (ImmutableClassesGiraphConfiguration<I, Writable, E>)
            configuration);

    edgeReader.initialize(inputSplit, context);
    // Set aggregator usage to edge reader
    edgeReader.setWorkerAggregatorUse(aggregatorUsage);

    long inputSplitEdgesLoaded = 0;
    long inputSplitEdgesFiltered = 0;

    while (edgeReader.nextEdge()) {
      I sourceId = edgeReader.getCurrentSourceId();
      Edge<I, E> readerEdge = edgeReader.getCurrentEdge();
      if (sourceId == null) {
        throw new IllegalArgumentException(
            "readInputSplit: Edge reader returned an edge " +
                "without a source vertex id!  - " + readerEdge);
      }
      if (readerEdge.getTargetVertexId() == null) {
        throw new IllegalArgumentException(
            "readInputSplit: Edge reader returned an edge " +
                "without a target vertex id!  - " + readerEdge);
      }
      if (readerEdge.getValue() == null) {
        throw new IllegalArgumentException(
            "readInputSplit: Edge reader returned an edge " +
                "without a value!  - " + readerEdge);
      }

      ++inputSplitEdgesLoaded;

      if (edgeInputFilter.dropEdge(sourceId, readerEdge)) {
        ++inputSplitEdgesFiltered;
        if (inputSplitEdgesFiltered % EDGES_FILTERED_UPDATE_PERIOD == 0) {
          totalEdgesFiltered.inc(inputSplitEdgesFiltered);
          inputSplitEdgesFiltered = 0;
        }
        continue;
      }

      workerClientRequestProcessor.sendEdgeRequest(sourceId, readerEdge);

      // Update status every EDGES_UPDATE_PERIOD edges
      if (inputSplitEdgesLoaded % EDGES_UPDATE_PERIOD == 0) {
        totalEdgesMeter.mark(EDGES_UPDATE_PERIOD);
        LoggerUtils.setStatusAndLog(context, LOG, Level.INFO,
            "readEdgeInputSplit: Loaded " +
                totalEdgesMeter.count() + " edges at " +
                totalEdgesMeter.meanRate() + " edges/sec " +
                MemoryUtils.getRuntimeMemoryStats());
      }

      // For sampling, or to limit outlier input splits, the number of
      // records per input split can be limited
      if (inputSplitMaxEdges > 0 &&
          inputSplitEdgesLoaded >= inputSplitMaxEdges) {
        if (LOG.isInfoEnabled()) {
          LOG.info("readInputSplit: Leaving the input " +
              "split early, reached maximum edges " +
              inputSplitEdgesLoaded);
        }
        break;
      }
    }
    edgeReader.close();

    totalEdgesFiltered.inc(inputSplitEdgesFiltered);
    totalEdgesMeter.mark(inputSplitEdgesLoaded % EDGES_UPDATE_PERIOD);

    return new VertexEdgeCount(0, inputSplitEdgesLoaded);
  }
}
