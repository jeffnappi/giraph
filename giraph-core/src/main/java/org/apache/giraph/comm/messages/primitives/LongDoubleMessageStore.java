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

package org.apache.giraph.comm.messages.primitives;

import org.apache.giraph.bsp.CentralizedServiceWorker;
import org.apache.giraph.combiner.Combiner;
import org.apache.giraph.comm.messages.MessageStore;
import org.apache.giraph.partition.Partition;
import org.apache.giraph.utils.ByteArrayVertexIdMessages;
import org.apache.giraph.utils.EmptyIterable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Special message store to be used when ids are LongWritable and messages
 * are DoubleWritable and combiner is used.
 * Uses fastutil primitive maps in order to decrease number of objects and
 * get better performance.
 */
public class LongDoubleMessageStore
    implements MessageStore<LongWritable, DoubleWritable> {
  /** Map from partition id to map from vertex id to message */
  private final Int2ObjectOpenHashMap<Long2DoubleOpenHashMap> map;
  /** Message combiner */
  private final Combiner<LongWritable, DoubleWritable> combiner;
  /** Service worker */
  private final CentralizedServiceWorker<LongWritable, ?, ?> service;

  /**
   * Constructor
   *
   * @param service Service worker
   * @param combiner Message combiner
   */
  public LongDoubleMessageStore(
      CentralizedServiceWorker<LongWritable, ?, ?> service,
      Combiner<LongWritable, DoubleWritable> combiner) {
    this.service = service;
    this.combiner = combiner;

    map = new Int2ObjectOpenHashMap<Long2DoubleOpenHashMap>();
    for (int partitionId : service.getPartitionStore().getPartitionIds()) {
      Partition<LongWritable, ?, ?> partition =
          service.getPartitionStore().getPartition(partitionId);
      Long2DoubleOpenHashMap partitionMap =
          new Long2DoubleOpenHashMap((int) partition.getVertexCount());
      map.put(partitionId, partitionMap);
    }
  }

  /**
   * Get map which holds messages for partition which vertex belongs to.
   *
   * @param vertexId Id of the vertex
   * @return Map which holds messages for partition which vertex belongs to.
   */
  private Long2DoubleOpenHashMap getPartitionMap(LongWritable vertexId) {
    return map.get(service.getPartitionId(vertexId));
  }

  @Override
  public void addPartitionMessages(int partitionId,
      ByteArrayVertexIdMessages<LongWritable, DoubleWritable> messages) throws
      IOException {
    LongWritable reusableVertexId = new LongWritable();
    DoubleWritable reusableMessage = new DoubleWritable();
    DoubleWritable reusableCurrentMessage = new DoubleWritable();

    Long2DoubleOpenHashMap partitionMap = map.get(partitionId);
    synchronized (partitionMap) {
      ByteArrayVertexIdMessages<LongWritable,
          DoubleWritable>.VertexIdMessageIterator
          iterator = messages.getVertexIdMessageIterator();
      while (iterator.hasNext()) {
        iterator.next();
        long vertexId = iterator.getCurrentVertexId().get();
        double message = iterator.getCurrentMessage().get();
        if (partitionMap.containsKey(vertexId)) {
          reusableVertexId.set(vertexId);
          reusableMessage.set(message);
          reusableCurrentMessage.set(partitionMap.get(vertexId));
          combiner.combine(reusableVertexId, reusableCurrentMessage,
              reusableMessage);
          message = reusableCurrentMessage.get();
        }
        partitionMap.put(vertexId, message);
      }
    }
  }

  @Override
  public void clearPartition(int partitionId) throws IOException {
    map.get(partitionId).clear();
  }

  @Override
  public boolean hasMessagesForVertex(LongWritable vertexId) {
    return getPartitionMap(vertexId).containsKey(vertexId.get());
  }

  @Override
  public Iterable<DoubleWritable> getVertexMessages(
      LongWritable vertexId) throws IOException {
    Long2DoubleOpenHashMap partitionMap = getPartitionMap(vertexId);
    if (!partitionMap.containsKey(vertexId.get())) {
      return EmptyIterable.get();
    } else {
      return Collections.singleton(
          new DoubleWritable(partitionMap.get(vertexId.get())));
    }
  }

  @Override
  public void clearVertexMessages(LongWritable vertexId) throws IOException {
    getPartitionMap(vertexId).remove(vertexId.get());
  }

  @Override
  public void clearAll() throws IOException {
    map.clear();
  }

  @Override
  public Iterable<LongWritable> getPartitionDestinationVertices(
      int partitionId) {
    Long2DoubleOpenHashMap partitionMap = map.get(partitionId);
    List<LongWritable> vertices =
        Lists.newArrayListWithCapacity(partitionMap.size());
    LongIterator iterator = partitionMap.keySet().iterator();
    while (iterator.hasNext()) {
      vertices.add(new LongWritable(iterator.nextLong()));
    }
    return vertices;
  }

  @Override
  public void writePartition(DataOutput out,
      int partitionId) throws IOException {
    Long2DoubleOpenHashMap partitionMap = map.get(partitionId);
    out.writeInt(partitionMap.size());
    ObjectIterator<Long2DoubleMap.Entry> iterator =
        partitionMap.long2DoubleEntrySet().fastIterator();
    while (iterator.hasNext()) {
      Long2DoubleMap.Entry entry = iterator.next();
      out.writeLong(entry.getLongKey());
      out.writeDouble(entry.getDoubleValue());
    }
  }

  @Override
  public void readFieldsForPartition(DataInput in,
      int partitionId) throws IOException {
    int size = in.readInt();
    Long2DoubleOpenHashMap partitionMap = new Long2DoubleOpenHashMap(size);
    while (size-- > 0) {
      long vertexId = in.readLong();
      double message = in.readDouble();
      partitionMap.put(vertexId, message);
    }
    synchronized (map) {
      map.put(partitionId, partitionMap);
    }
  }
}
