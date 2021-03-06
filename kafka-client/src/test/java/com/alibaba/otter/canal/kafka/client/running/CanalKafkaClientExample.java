package com.alibaba.otter.canal.kafka.client.running;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.alibaba.otter.canal.kafka.client.KafkaCanalConnector;
import com.alibaba.otter.canal.kafka.client.KafkaCanalConnectors;
import com.alibaba.otter.canal.protocol.Message;

/**
 * Kafka client example
 *
 * @author machengyuan @ 2018-6-12
 * @version 1.0.0
 */
public class CanalKafkaClientExample {

    protected final static Logger logger = LoggerFactory.getLogger(CanalKafkaClientExample.class);

    private KafkaCanalConnector connector;

    private static volatile boolean running = false;

    private Thread thread = null;

    private Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {

        public void uncaughtException(Thread t, Throwable e) {
            logger.error("parse events has an error", e);
        }
    };

    public CanalKafkaClientExample(String zkServers, String servers, String topic, Integer partition, String groupId) {
        connector = KafkaCanalConnectors.newKafkaConnector(zkServers, servers, topic, partition, groupId);
    }

    public static void main(String[] args) {
        try {
            final CanalKafkaClientExample kafkaCanalClientExample = new CanalKafkaClientExample(AbstractKafkaTest.zkServers,
                    AbstractKafkaTest.servers,
                    AbstractKafkaTest.topic,
                    AbstractKafkaTest.partition,
                    AbstractKafkaTest.groupId);
            logger.info("## start the kafka consumer: {}-{}", AbstractKafkaTest.topic, AbstractKafkaTest.groupId);
            kafkaCanalClientExample.start();
            logger.info("## the canal kafka consumer is running now ......");
            Runtime.getRuntime().addShutdownHook(new Thread() {

                public void run() {
                    try {
                        logger.info("## stop the kafka consumer");
                        kafkaCanalClientExample.stop();
                    } catch (Throwable e) {
                        logger.warn("##something goes wrong when stopping kafka consumer:", e);
                    } finally {
                        logger.info("## kafka consumer is down.");
                    }
                }

            });
            while (running)
                ;
        } catch (Throwable e) {
            logger.error("## Something goes wrong when starting up the kafka consumer:", e);
            System.exit(0);
        }
    }

    public void start() {
        Assert.notNull(connector, "connector is null");
        thread = new Thread(new Runnable() {

            public void run() {
                process();
            }
        });
        thread.setUncaughtExceptionHandler(handler);
        thread.start();
        running = true;
    }

    public void stop() {
        if (!running) {
            return;
        }
        connector.stopRunning();
        running = false;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private void process() {
        while (!running)
            ;
        while (running) {
            try {
                connector.connect();
                connector.subscribe();
                while (running) {
                    try {
                        Message message = connector.getWithoutAck(1L, TimeUnit.SECONDS); // ??????message
                        if (message == null) {
                            continue;
                        }
                        long batchId = message.getId();
                        int size = message.getEntries().size();
                        if (batchId == -1 || size == 0) {
                            // try {
                            // Thread.sleep(1000);
                            // } catch (InterruptedException e) {
                            // }
                        } else {
                            // printSummary(message, batchId, size);
                            // printEntry(message.getEntries());
                            logger.info(message.toString());
                        }

                        connector.ack(); // ????????????
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }

        try {
            connector.unsubscribe();
        } catch (WakeupException e) {
            // No-op. Continue process
        }
        connector.disconnnect();
    }
}
