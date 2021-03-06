/*
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may
 * not use this file except in compliance with the License. A copy of the
 * License is located at
 *
 *    http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.flink.refarch;

import com.amazonaws.flink.refarch.events.TripEvent;
import com.amazonaws.flink.refarch.events.WatermarkEvent;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.*;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.cli.*;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.*;


public class StreamPopulator
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamPopulator.class);

    /** sent a watermark every WATERMARK_MILLIS ms or WATERMARK_EVENT_COUNT events, whaterver comes first */
    private static final long WATERMARK_MILLIS = 10_000;
    private static final long WATERMARK_EVENT_COUNT = 10_000;

    private static final long MIN_SLEEP_MILLIS = 5;
    private static final long STAT_INTERVAL_MILLIS = 60_000;

    private final String streamName;
    private final float speedupFactor;
    private final Boolean noWatermark;
    private final KinesisProducer kinesisProducer;
    private final AmazonKinesis kinesisClient;
    private final TaxiEventReader taxiEventReader;

    private final SortedSet<TripEvent> inflightEvents = Collections.synchronizedSortedSet(new TreeSet<>());


    public StreamPopulator(String region, String bucketName, String objectPrefix, String streamName, float speedupFactor, boolean noWatermark) {
        KinesisProducerConfiguration producerConfiguration = new KinesisProducerConfiguration()
                .setRegion(region)
                .setRecordMaxBufferedTime(3000)
                .setAggregationEnabled(false);

        AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_1).build();
        AmazonKinesis kinesis = AmazonKinesisClientBuilder.standard().withRegion(region).build();

        this.streamName = streamName;
        this.speedupFactor = speedupFactor;
        this.noWatermark = noWatermark;
        this.kinesisClient = kinesis;
        this.kinesisProducer = new KinesisProducer(producerConfiguration);
        this.taxiEventReader = new TaxiEventReader(s3, bucketName, objectPrefix);

        LOG.info("Starting to populate stream {}", streamName);
    }

    public static void main( String[] args ) throws ParseException {
        Options options = new Options()
                .addOption("region", true, "the region containing the kinesis stream")
                .addOption("bucket", true, "the bucket containing the raw event data")
                .addOption("prefix", true, "the prefix of the objects containing the raw event data")
                .addOption("stream", true, "the name of the kinesis stream the events are sent to")
                .addOption("speedup", true, "the speedup factor for replaying events into the kinesis stream")
                .addOption("noWatermarks", "don't injest watermark events into the Kinesis stream")
                .addOption("help", "print this help message");

        CommandLine line = new DefaultParser().parse(options, args);

        if (line.hasOption("help")) {
            new HelpFormatter().printHelp(MethodHandles.lookup().lookupClass().getName(), options);
        } else {
            new StreamPopulator(
                    line.getOptionValue("region", "eu-west-1"),
                    line.getOptionValue("bucket", "aws-bigdata-blog"),
                    line.getOptionValue("prefix", "artifacts/flink-refarch/data/"),
                    line.getOptionValue("stream", "taxi-trip-events"),
                    Float.valueOf(line.getOptionValue("speedup", "1440")),
                    line.hasOption("noWatermark")
            ).populate();
        }
    }

    private void sentWatermark(String streamName, long timestamp) {
        DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
        describeStreamRequest.setStreamName(streamName);
        List<Shard> shards = new ArrayList<>();
        String exclusiveStartShardId = null;

        try {
            do {
                describeStreamRequest.setExclusiveStartShardId(exclusiveStartShardId);
                DescribeStreamResult describeStreamResult = kinesisClient.describeStream(describeStreamRequest);
                shards.addAll(describeStreamResult.getStreamDescription().getShards());

                if (describeStreamResult.getStreamDescription().getHasMoreShards() && shards.size() > 0) {
                    exclusiveStartShardId = shards.get(shards.size() - 1).getShardId();
                } else {
                    exclusiveStartShardId = null;
                }
            } while (exclusiveStartShardId != null);

            shards.stream()
                    .map(shard -> new PutRecordRequest()
                            .withStreamName(streamName)
                            .withData(new WatermarkEvent(timestamp).payload)
                            .withPartitionKey("23")
                            .withExplicitHashKey(shard.getHashKeyRange().getStartingHashKey()))
                    .map(kinesisClient::putRecord)
                    .forEach(putRecordResult -> LOG.trace("send watermark {} to shard {}", new DateTime(timestamp), putRecordResult.getShardId()));

            LOG.debug("send watermark {}", new DateTime(timestamp));
        } catch (LimitExceededException | ProvisionedThroughputExceededException e) {
            LOG.warn("skipping watermark due to limit exceeded exception");
        }

    }

    private void populate() {
        long lastWatermark = 0, lastWatermarkSentTime = 0;
        long watermarkBatchEventCount = 0, statisticsBatchEventCount = 0;
        long statisticsLastOutputTimeslot = 0;

        TripEvent nextEvent = taxiEventReader.next();

        final long timeZeroSystem = System.currentTimeMillis();
        final long timeZeroLog = nextEvent.timestamp;

        while (true) {
            double timeDeltaSystem = (System.currentTimeMillis() - timeZeroSystem)*speedupFactor;
            long timeDeltaLog = nextEvent.timestamp - timeZeroLog;
            double replayTimeGap = timeDeltaSystem - timeDeltaLog;

            if (replayTimeGap < 0) {
                try {
                    long sleepTime = (long) Math.max(-replayTimeGap / speedupFactor, MIN_SLEEP_MILLIS);

                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    LOG.error(e.getMessage());
                }
            } else {
                watermarkBatchEventCount++;
                statisticsBatchEventCount++;

                LOG.trace("sent event {}", nextEvent);

                ListenableFuture<UserRecordResult> f = kinesisProducer.addUserRecord(streamName, Integer.toString(nextEvent.hashCode()), nextEvent.payload);
                Futures.addCallback(f, new WatermarkTrackerCallback(nextEvent));

                if (taxiEventReader.hasNext()) {
                    nextEvent = taxiEventReader.next();
                } else {
                    break;
                }
            }

            long timeSinceLastWatermark = System.currentTimeMillis() - lastWatermarkSentTime;

            if (timeSinceLastWatermark >= WATERMARK_MILLIS || watermarkBatchEventCount >= WATERMARK_EVENT_COUNT) {
                long watermark;
                try {
                    watermark = inflightEvents.first().timestamp - 1;
                } catch (NoSuchElementException e) {
                    watermark = nextEvent.timestamp - 1;
                }

                if (!noWatermark) {
                    sentWatermark(streamName, watermark);
                }

                watermarkBatchEventCount = 0;
                lastWatermark = watermark;
                lastWatermarkSentTime = System.currentTimeMillis();
            }

            if ((System.currentTimeMillis()-timeZeroSystem)/STAT_INTERVAL_MILLIS != statisticsLastOutputTimeslot) {
                double statisticsBatchEventRate = Math.round(1000.0 * statisticsBatchEventCount / STAT_INTERVAL_MILLIS);
                long replayLag = Math.round(replayTimeGap/speedupFactor/1000);

                LOG.info("all events with dropoff time before {} have been sent ({} events/sec, {} sec replay lag)", new DateTime(lastWatermark), statisticsBatchEventRate, replayLag);

                statisticsBatchEventCount = 0;
                statisticsLastOutputTimeslot = (System.currentTimeMillis()-timeZeroSystem)/STAT_INTERVAL_MILLIS;
            }
        }

        kinesisProducer.flushSync();
        kinesisProducer.destroy();
    }


    class WatermarkTrackerCallback implements FutureCallback<UserRecordResult> {
        private final TripEvent event;

        WatermarkTrackerCallback(TripEvent event) {
            this.event = event;

            inflightEvents.add(event);
        }

        private void removeEvent() {
            inflightEvents.remove(event);
        }

        @Override
        public void onFailure(Throwable t) {
            LOG.warn("failed to send event {}", event);

            removeEvent();
        }

        @Override
        public void onSuccess(UserRecordResult result) {
            removeEvent();
        }
    }
}