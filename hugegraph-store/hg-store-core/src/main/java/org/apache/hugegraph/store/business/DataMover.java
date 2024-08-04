/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.store.business;

import java.util.List;

import org.apache.hugegraph.pd.grpc.Metapb;
import org.apache.hugegraph.store.cmd.BatchPutRequest;
import org.apache.hugegraph.store.cmd.CleanDataRequest;
import org.apache.hugegraph.store.cmd.HgCmdClient;
import org.apache.hugegraph.store.cmd.UpdatePartitionResponse;

import com.alipay.sofa.jraft.Status;

/**
 * Data Transfer Interface to realize partition and mergers, support transit transmission data
 */
public interface DataMover {

    void setBusinessHandler(BusinessHandler handler);

    void setCmdClient(HgCmdClient client);

    /**
     * Data in Copy Distribesource to other partitions Targets
     * A partition, migrate to multiple partitions
     *
     * @param source  source partition
     * @param targets target partitions
     * @return execution status
     * @throws Exception execution exception
     */
    Status moveData(Metapb.Partition source, List<Metapb.Partition> targets) throws Exception;

    /**
     * All data of Willsource Target is copied to target
     * Move from one partition to another partition
     *
     * @param source source partition
     * @param target target partition
     * @return execution result
     * @throws Exception execution exception
     */
    Status moveData(Metapb.Partition source, Metapb.Partition target) throws Exception;

    // The partition status between the synchronous copy
    UpdatePartitionResponse updatePartitionState(Metapb.Partition partition,
                                                 Metapb.PartitionState state);

    // The scope of the partition between the synchronous copy
    UpdatePartitionResponse updatePartitionRange(Metapb.Partition partition, int startKey,
                                                 int endKey);

    // Clean up invalid data in partition partition
    void cleanData(Metapb.Partition partition);

    // data input
    void doWriteData(BatchPutRequest request);

    void doCleanData(CleanDataRequest request);
}
