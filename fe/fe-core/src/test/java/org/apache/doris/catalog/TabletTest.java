// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog;

import org.apache.doris.catalog.Replica.ReplicaState;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.Pair;
import org.apache.doris.common.io.Text;
import org.apache.doris.persist.gson.GsonUtils;
import org.apache.doris.system.Backend;
import org.apache.doris.system.SystemInfoService;
import org.apache.doris.thrift.TStorageMedium;

import com.google.common.collect.Sets;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class TabletTest {

    private Tablet tablet;
    private Replica replica1;
    private Replica replica2;
    private Replica replica3;

    private TabletInvertedIndex invertedIndex;
    private SystemInfoService  infoService;

    @Mocked
    private Env env;

    @Before
    public void makeTablet() {
        invertedIndex = new TabletInvertedIndex();
        infoService = new SystemInfoService();
        for (long beId = 1L; beId <= 4L; beId++) {
            Backend be = new Backend(beId, "127.0.0." + beId, 8030);
            be.setAlive(true);
            infoService.addBackend(be);
        }
        new Expectations(env) {
            {
                Env.getCurrentEnvJournalVersion();
                minTimes = 0;
                result = FeConstants.meta_version;

                Env.getCurrentInvertedIndex();
                minTimes = 0;
                result = invertedIndex;

                Env.getCurrentSystemInfo();
                minTimes = 0;
                result = infoService;

                Env.isCheckpointThread();
                minTimes = 0;
                result = false;
            }
        };

        tablet = new Tablet(1);
        TabletMeta tabletMeta = new TabletMeta(10, 20, 30, 40, 1, TStorageMedium.HDD);
        invertedIndex.addTablet(1, tabletMeta);
        replica1 = new Replica(1L, 1L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        replica2 = new Replica(2L, 2L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        replica3 = new Replica(3L, 3L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        tablet.addReplica(replica1);
        tablet.addReplica(replica2);
        tablet.addReplica(replica3);
    }

    @Test
    public void getMethodTest() {
        Assert.assertEquals(replica1, tablet.getReplicaById(replica1.getId()));
        Assert.assertEquals(replica2, tablet.getReplicaById(replica2.getId()));
        Assert.assertEquals(replica3, tablet.getReplicaById(replica3.getId()));

        Assert.assertEquals(3, tablet.getReplicas().size());
        Assert.assertEquals(replica1, tablet.getReplicaByBackendId(replica1.getBackendIdWithoutException()));
        Assert.assertEquals(replica2, tablet.getReplicaByBackendId(replica2.getBackendIdWithoutException()));
        Assert.assertEquals(replica3, tablet.getReplicaByBackendId(replica3.getBackendIdWithoutException()));


        long newTabletId = 20000;
        tablet.setTabletId(newTabletId);
        Assert.assertEquals("tabletId=" + newTabletId, tablet.toString());
    }

    @Test
    public void deleteReplicaTest() {
        // delete replica1
        Assert.assertTrue(tablet.deleteReplicaByBackendId(replica1.getBackendIdWithoutException()));
        Assert.assertNull(tablet.getReplicaById(replica1.getId()));

        // err: re-delete replica1
        Assert.assertFalse(tablet.deleteReplicaByBackendId(replica1.getBackendIdWithoutException()));
        Assert.assertFalse(tablet.deleteReplica(replica1));
        Assert.assertNull(tablet.getReplicaById(replica1.getId()));

        // delete replica2
        Assert.assertTrue(tablet.deleteReplica(replica2));
        Assert.assertEquals(1, tablet.getReplicas().size());

        // clear replicas
        tablet.clearReplica();
        Assert.assertEquals(0, tablet.getReplicas().size());
    }

    @Test
    public void testSerialization() throws Exception {
        final Path path = Files.createTempFile("olapTabletTest", "tmp");
        DataOutputStream dos = new DataOutputStream(Files.newOutputStream(path));
        Text.writeString(dos, GsonUtils.GSON.toJson(tablet));
        dos.flush();
        dos.close();

        // 2. Read a object from file
        DataInputStream dis = new DataInputStream(Files.newInputStream(path));
        Tablet rTablet1 = GsonUtils.GSON.fromJson(Text.readString(dis), Tablet.class);
        Assert.assertEquals(1, rTablet1.getId());
        Assert.assertEquals(3, rTablet1.getReplicas().size());
        Assert.assertEquals(rTablet1.getReplicas().get(0).getVersion(), rTablet1.getReplicas().get(1).getVersion());

        Assert.assertEquals(rTablet1, tablet);
        Assert.assertEquals(rTablet1, rTablet1);

        Tablet tablet2 = new Tablet(1);
        Replica replica1 = new Replica(1L, 1L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        Replica replica2 = new Replica(2L, 2L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        Replica replica3 = new Replica(3L, 3L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0);
        tablet2.addReplica(replica1);
        tablet2.addReplica(replica2);
        Assert.assertNotEquals(tablet2, tablet);
        tablet2.addReplica(replica3);
        Assert.assertEquals(tablet2, tablet);

        Tablet tablet3 = new Tablet(1);
        tablet3.addReplica(replica1);
        tablet3.addReplica(replica2);
        tablet3.addReplica(new Replica(4L, 4L, 100L, 0, 200000L, 0, 3000L, ReplicaState.NORMAL, 0, 0));
        Assert.assertNotEquals(tablet3, tablet);

        dis.close();
        Files.delete(path);
    }

    /**
     * check the tablet's Tablet.TabletStatus, the right location is [1 2 3]
     * @param backendId2ReplicaIsBad beId -> if replica is a bad replica
     */
    @SafeVarargs
    private final void testTabletColocateHealthStatus0(Tablet.TabletStatus exceptedTabletStatus,
            Pair<Long, Boolean>... backendId2ReplicaIsBad) {
        Tablet tablet = new Tablet(1);
        int replicaId = 1;
        for (Pair<Long, Boolean> pair : backendId2ReplicaIsBad) {
            long versionAndSuccessVersion = 100L;
            long lastFailVersion = -1L;
            if (pair.second) {
                versionAndSuccessVersion = 99L;
                lastFailVersion = 100L;
            }
            tablet.addReplica(new Replica(replicaId++, pair.first, versionAndSuccessVersion, 0,
                    200000L, 0, 3000L, ReplicaState.NORMAL, lastFailVersion, versionAndSuccessVersion));
        }
        Assert.assertEquals(tablet.getColocateHealth(100L, new ReplicaAllocation((short) 3),
                Sets.newHashSet(1L, 2L, 3L)).status, exceptedTabletStatus);
    }

    @Test
    public void testTabletColocateHealthStatus() {
        // [1 2 4]
        testTabletColocateHealthStatus0(
                Tablet.TabletStatus.COLOCATE_MISMATCH,
                Pair.of(1L, false), Pair.of(2L, false), Pair.of(4L, false)
        );

        // [1 2 3(bad)]
        testTabletColocateHealthStatus0(
                Tablet.TabletStatus.VERSION_INCOMPLETE,
                Pair.of(1L, false), Pair.of(2L, false), Pair.of(3L, true)
        );

        // 1 2 3 4(good)
        testTabletColocateHealthStatus0(
                Tablet.TabletStatus.COLOCATE_REDUNDANT,
                Pair.of(1L, false), Pair.of(2L, false), Pair.of(3L, false), Pair.of(4L, false)
        );

        // [1 2 3 4(bad)]
        testTabletColocateHealthStatus0(
                Tablet.TabletStatus.COLOCATE_REDUNDANT,
                Pair.of(1L, false), Pair.of(2L, false), Pair.of(3L, false), Pair.of(4L, true)
        );
    }

    @Test
    public void testGetMinReplicaRowCount() {
        Tablet t = new Tablet(1);
        long row = t.getMinReplicaRowCount(1);
        Assert.assertEquals(0, row);

        Replica r1 = new Replica(1, 1, 10, 0, 0, 0, 100, ReplicaState.NORMAL, 0, 10);
        t.addReplica(r1);
        row = t.getMinReplicaRowCount(10);
        Assert.assertEquals(100, row);

        row = t.getMinReplicaRowCount(11);
        Assert.assertEquals(0, row);

        Replica r2 = new Replica(2, 2, 10, 0, 0, 0, 110, ReplicaState.NORMAL, 0, 10);
        Replica r3 = new Replica(3, 3, 10, 0, 0, 0, 90, ReplicaState.NORMAL, 0, 10);
        t.addReplica(r2);
        t.addReplica(r3);
        row = t.getMinReplicaRowCount(11);
        Assert.assertEquals(0, row);
        row = t.getMinReplicaRowCount(9);
        Assert.assertEquals(90, row);

        r3.setBad(true);
        row = t.getMinReplicaRowCount(9);
        Assert.assertEquals(100, row);

        r3.setBad(false);
        row = t.getMinReplicaRowCount(9);
        Assert.assertEquals(90, row);

        r2.updateVersion(11);
        row = t.getMinReplicaRowCount(9);
        Assert.assertEquals(110, row);
    }
}
