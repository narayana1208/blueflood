/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.google.common.collect.Lists;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.ColumnFamily;
import com.rackspacecloud.blueflood.io.AstyanaxReader;
import com.rackspacecloud.blueflood.io.AstyanaxWriter;
import com.rackspacecloud.blueflood.io.CassandraModel;
import com.rackspacecloud.blueflood.io.IntegrationTestBase;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.types.*;
import com.rackspacecloud.blueflood.utils.TimeValue;
import com.rackspacecloud.blueflood.utils.Util;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Some of these tests here were horribly contrived to mimic behavior in Writer. The problem with this approach is that
 * when logic in Writer changes, these tests can break unless the logic is changed here too. */
public class MetricsIntegrationTest extends IntegrationTestBase {
    
    // returns a collection all checks that were written at some point.
    // this was a lot cooler back when the slot changed with time.
    private Collection<Locator> writeLocatorsOnly(int hours) throws Exception {
        // duplicate the logic from Writer.writeFull() that inserts locator rows.
        final String tenantId = "ac" + randString(8);
        final List<Locator> locators = new ArrayList<Locator>();
        for (int i = 0; i < hours; i++) {
            locators.add(Locator.createLocatorFromPathComponents(tenantId, "test:locator:inserts:" + i));
        }

        AstyanaxTester at = new AstyanaxTester();
        MutationBatch mb = at.createMutationBatch();

        for (Locator locator : locators) {
            int shard = Util.computeShard(locator.toString());
            mb.withRow(at.getLocatorCF(), (long)shard)
                    .putColumn(locator, "", 100000);
        }
        mb.execute();

        return locators;
    }

    private void writeFullData(
            Locator locator,
            long baseMillis, 
            int hours,
            AstyanaxWriter writer) throws Exception {
        // insert something every minute for 48h
        for (int i = 0; i < 60 * hours; i++) {
            final long curMillis = baseMillis + i * 60000;
            List<Metric> metrics = new ArrayList<Metric>();
            metrics.add(getRandomIntMetric(locator, curMillis));
            writer.insertFull(metrics);
        }
    }

    @Test
    public void testLocatorsWritten() throws Exception {
        Collection<Locator> locators = writeLocatorsOnly(48);
        AstyanaxReader r = AstyanaxReader.getInstance();

        Set<String> actualLocators = new HashSet<String>();
        for (Locator locator : locators) {
            for (Locator databaseLocator : r.getLocatorsToRollup(Util.computeShard(locator.toString()))) {
                actualLocators.add(databaseLocator.toString());
            }
        }
        Assert.assertEquals(48, actualLocators.size());
    }

    @Test
    public void testRollupGenerationSimple() throws Exception {
        AstyanaxWriter writer = AstyanaxWriter.getInstance();
        AstyanaxReader reader = AstyanaxReader.getInstance();
        final long baseMillis = 1333635148000L; // some point during 5 April 2012.
        int hours = 48;
        final String acctId = "ac" + IntegrationTestBase.randString(8);
        final String metricName = "fooService,barServer," + randString(8);
        final long endMillis = baseMillis + (1000 * 60 * 60 * hours);
        final Locator locator = Locator.createLocatorFromPathComponents(acctId, metricName);

        writeFullData(locator, baseMillis, hours, writer);

        // FULL -> 5m
        ArrayList<SingleRollupWriteContext> writes = new ArrayList<SingleRollupWriteContext>();
        for (Range range : Range.getRangesToRollup(Granularity.FULL, baseMillis, endMillis)) {
            // each range should produce one average
            Points<SimpleNumber> input = reader.getDataToRoll(SimpleNumber.class, locator, range, CassandraModel.CF_METRICS_FULL);
            BasicRollup basicRollup = BasicRollup.buildRollupFromRawSamples(input);
            HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRawSamples(input);

            writes.add(new SingleRollupWriteContext(basicRollup,
                    locator,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.FULL.coarser()),
                    range.start));
            writes.add(new SingleRollupWriteContext(histogramRollup,
                    locator,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_5),
                    range.start));
        }
        writer.insertRollups(writes);

        // 5m -> 20m
        writes.clear();

        for (Range range : Range.getRangesToRollup(Granularity.MIN_5, baseMillis, endMillis)) {
            Points<BasicRollup> input = reader.getDataToRoll(BasicRollup.class, locator, range,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_5));
            BasicRollup basicRollup = BasicRollup.buildRollupFromRollups(input);
            writes.add(new SingleRollupWriteContext(basicRollup,
                    locator,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_5.coarser()),
                    range.start));

            Points<HistogramRollup> histInput = reader.getDataToRoll(HistogramRollup.class, locator, range,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_5));
            HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRollups(histInput);
            writes.add(new SingleRollupWriteContext(histogramRollup,
                    locator,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_20),
                    range.start));
        }
        writer.insertRollups(writes);

        // 20m -> 60m
        writes.clear();
        for (Range range : Range.getRangesToRollup(Granularity.MIN_20, baseMillis, endMillis)) {
            Points<BasicRollup> input = reader.getDataToRoll(BasicRollup.class, locator, range,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_20));
            BasicRollup basicRollup = BasicRollup.buildRollupFromRollups(input);
            writes.add(new SingleRollupWriteContext(basicRollup,
                    locator,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_20.coarser()),
                    range.start));

            Points<HistogramRollup> histInput = reader.getDataToRoll(HistogramRollup.class, locator, range,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_5));
            HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRollups(histInput);
            writes.add(new SingleRollupWriteContext(histogramRollup,
                    locator,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_60),
                    range.start));
        }
        writer.insertRollups(writes);

        // 60m -> 240m
        writes.clear();
        for (Range range : Range.getRangesToRollup(Granularity.MIN_60, baseMillis, endMillis)) {
            Points<BasicRollup> input = reader.getDataToRoll(BasicRollup.class, locator, range,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_60));

            BasicRollup basicRollup = BasicRollup.buildRollupFromRollups(input);
            writes.add(new SingleRollupWriteContext(basicRollup,
                    locator,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_60.coarser()),
                    range.start));

            Points<HistogramRollup> histInput = reader.getDataToRoll(HistogramRollup.class, locator, range,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_5));
            HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRollups(histInput);
            writes.add(new SingleRollupWriteContext(histogramRollup,
                    locator,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_240),
                    range.start));
        }
        writer.insertRollups(writes);

        // 240m -> 1440m
        writes.clear();
        for (Range range : Range.getRangesToRollup(Granularity.MIN_240, baseMillis, endMillis)) {
            Points<BasicRollup> input = reader.getDataToRoll(BasicRollup.class, locator, range,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_240));
            BasicRollup basicRollup = BasicRollup.buildRollupFromRollups(input);
            writes.add(new SingleRollupWriteContext(basicRollup,
                    locator,
                    CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_240.coarser()),
                    range.start));

            Points<HistogramRollup> histInput = reader.getDataToRoll(HistogramRollup.class, locator, range,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_5));
            HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRollups(histInput);
            writes.add(new SingleRollupWriteContext(histogramRollup,
                    locator,
                    CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_1440),
                    range.start));
        }
        writer.insertRollups(writes);

        // verify the number of points in 48h worth of rollups. 
        Range range = new Range(Granularity.MIN_1440.snapMillis(baseMillis), Granularity.MIN_1440.snapMillis(endMillis + Granularity.MIN_1440.milliseconds()));
        Points<BasicRollup> input = reader.getDataToRoll(BasicRollup.class, locator, range,
                CassandraModel.getColumnFamily(BasicRollup.class, Granularity.MIN_1440));
        BasicRollup basicRollup = BasicRollup.buildRollupFromRollups(input);
        Assert.assertEquals(60 * hours, basicRollup.getCount());

        Points<HistogramRollup> histInput = reader.getDataToRoll(HistogramRollup.class, locator, range,
                CassandraModel.getColumnFamily(HistogramRollup.class, Granularity.MIN_1440));
        HistogramRollup histogramRollup = HistogramRollup.buildRollupFromRollups(histInput);
        Assert.assertTrue(histogramRollup.getBins().size() > 0);
        Assert.assertTrue("Number of bins is " + histogramRollup.getBins().size(),
                histogramRollup.getBins().size() <= HistogramRollup.MAX_BIN_SIZE);
    }

    @Test
    public void testSimpleInsertAndGet() throws Exception {
        AstyanaxWriter writer = AstyanaxWriter.getInstance();
        AstyanaxReader reader = AstyanaxReader.getInstance();
        final long baseMillis = 1333635148000L; // some point during 5 April 2012.
        long lastMillis = baseMillis + (300 * 1000); // 300 seconds.
        final String acctId = "ac" + IntegrationTestBase.randString(8);
        final String metricName = "fooService,barServer," + randString(8);

        final Locator locator  = Locator.createLocatorFromPathComponents(acctId, metricName);

        Set<Long> expectedTimestamps = new HashSet<Long>();
        // insert something every 30s for 5 mins.
        for (int i = 0; i < 10; i++) {
            final long curMillis = baseMillis + (i * 30000); // 30 seconds later.
            expectedTimestamps.add(curMillis);
            List<Metric> metrics = new ArrayList<Metric>();
            metrics.add(getRandomIntMetric(locator, curMillis));
            writer.insertFull(metrics);
        }
        
        Set<Long> actualTimestamps = new HashSet<Long>();
        // get back the cols that were written from start to stop.

        Points<SimpleNumber> points = reader.getDataToRoll(SimpleNumber.class, locator, new Range(baseMillis, lastMillis),
                CassandraModel.getColumnFamily(BasicRollup.class, Granularity.FULL));
        actualTimestamps = points.getPoints().keySet();
        Assert.assertEquals(expectedTimestamps, actualTimestamps);
    }

    @Test
    public void testConsecutiveWriteAndRead() throws ConnectionException, IOException {
        AstyanaxWriter writer = AstyanaxWriter.getInstance();
        AstyanaxReader reader = AstyanaxReader.getInstance();
        final long baseMillis = 1333635148000L;

        final Locator locator = Locator.createLocatorFromPathComponents("ac0001",
                "fooService,fooServer," + randString(8));

        final List<Metric> metrics = new ArrayList<Metric>();
        for (int i = 0; i < 100; i++) {
            final Metric metric = new Metric(locator, i, baseMillis + (i * 1000),
                    new TimeValue(1, TimeUnit.DAYS), "unknown");
            metrics.add(metric);
            writer.insertFull(metrics);
            metrics.clear();
        }
        
        int count = 0;
            ColumnFamily<Locator, Long> CF_metrics_full = CassandraModel.getColumnFamily(BasicRollup.class, Granularity.FULL);
        Points<SimpleNumber> points = reader.getDataToRoll(SimpleNumber.class, locator,
                new Range(baseMillis, baseMillis + 500000), CF_metrics_full);
        for (Map.Entry<Long, Points.Point<SimpleNumber>> data : points.getPoints().entrySet()) {
            Points.Point<SimpleNumber> point = data.getValue();
            Assert.assertEquals(count, point.getData().getValue());
            count++;
        }
    }

    @Test
    public void testShardStateWriteRead() throws Exception {
        final Collection<Integer> shards = Lists.newArrayList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        AstyanaxWriter writer = AstyanaxWriter.getInstance();

        // Simulate active or running state for all the slots for all granularities.
        for (int shard : shards) {
            Map<Granularity, Map<Integer, UpdateStamp>> allUpdates = new HashMap<Granularity, Map<Integer, UpdateStamp>>();
            for (Granularity granularity : Granularity.rollupGranularities()) {
                Map<Integer, UpdateStamp> updates = new HashMap<Integer, UpdateStamp>();
                for (int slot = 0; slot < granularity.numSlots(); slot++) {
                    updates.put(slot, new UpdateStamp(System.currentTimeMillis() - 10000, UpdateStamp.State.Active,
                            true));
                }
                allUpdates.put(granularity, updates);
            }
            writer.persistShardState(shard, allUpdates);
        }

        // Now simulate rolled up state for all the slots for all granularities.
        for (int shard : shards) {
            Map<Granularity, Map<Integer, UpdateStamp>> allUpdates = new HashMap<Granularity, Map<Integer, UpdateStamp>>();
            for (Granularity granularity : Granularity.rollupGranularities()) {
                Map<Integer, UpdateStamp> updates = new HashMap<Integer, UpdateStamp>();
                for (int slot = 0; slot < granularity.numSlots(); slot++) {
                    updates.put(slot, new UpdateStamp(System.currentTimeMillis(), UpdateStamp.State.Rolled,
                            true));
                }
                allUpdates.put(granularity, updates);
            }
            writer.persistShardState(shard, allUpdates);
        }

        // Now we would have the longest row for each shard because we filled all the slots.
        // Now test whether getAndUpdateShardState returns all the slots
        AstyanaxReader reader = AstyanaxReader.getInstance();
        ScheduleContext ctx = new ScheduleContext(System.currentTimeMillis(), shards);
        ShardStateManager shardStateManager = ctx.getShardStateManager();

        for (Integer shard : shards) {
            reader.getAndUpdateShardState(shardStateManager, shard);
            for (Granularity granularity : Granularity.rollupGranularities()) {
                ShardStateManager.SlotStateManager slotStateManager = shardStateManager.getSlotStateManager(shard, granularity);
                Assert.assertEquals(granularity.numSlots(), slotStateManager.getSlotStamps().size());
            }
        }
    }

    @Test
    public void testUpdateStampCoaelescing() throws Exception {
        final int shard = 24;
        final int slot = 16;
        AstyanaxWriter writer = AstyanaxWriter.getInstance();
        Map<Granularity, Map<Integer, UpdateStamp>> updates = new HashMap<Granularity, Map<Integer, UpdateStamp>>();
        Map<Integer, UpdateStamp> slotUpdates = new HashMap<Integer, UpdateStamp>();
        updates.put(Granularity.MIN_5, slotUpdates);
        
        long time = 1234;
        slotUpdates.put(slot, new UpdateStamp(time++, UpdateStamp.State.Active, true));
        writer.persistShardState(shard, updates);
        
        slotUpdates.put(slot, new UpdateStamp(time++, UpdateStamp.State.Rolled, true));
        writer.persistShardState(shard, updates);
        
        AstyanaxReader reader = AstyanaxReader.getInstance();
        ScheduleContext ctx = new ScheduleContext(System.currentTimeMillis(), Lists.newArrayList(shard));
        reader.getAndUpdateShardState(ctx.getShardStateManager(), shard);
        ShardStateManager shardStateManager = ctx.getShardStateManager();
        ShardStateManager.SlotStateManager slotStateManager = shardStateManager.getSlotStateManager(shard, Granularity.MIN_5);

        Assert.assertNotNull(slotStateManager.getSlotStamps());
        Assert.assertEquals(UpdateStamp.State.Rolled, slotStateManager.getSlotStamps().get(slot).getState());
    }
}
