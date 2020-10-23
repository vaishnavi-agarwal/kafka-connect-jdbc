/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.sink.integration;

import io.confluent.connect.jdbc.JdbcSinkConnector;
import io.confluent.connect.jdbc.BaseConnectorIT;
import io.confluent.connect.jdbc.MysqlTestContainer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.json.JsonConverterConfig;
import org.apache.kafka.connect.runtime.SinkConnectorConfig;
import org.apache.kafka.connect.storage.ConverterConfig;
import org.apache.kafka.connect.storage.ConverterType;
import org.apache.kafka.test.IntegrationTest;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Category(IntegrationTest.class)
public class JdbcSinkConnectorIT extends BaseConnectorIT {

  private static final Logger log = LoggerFactory.getLogger(JdbcSinkConnectorIT.class);
  private Map<String, String> props;
  private static final int NUM_RECORDS = 50;

  @ClassRule
  public static MysqlTestContainer mySqlContainer = new MysqlTestContainer();

  @Before
  public void setup() throws Exception {
    startConnect();
    connect.kafka().createTopic(KAFKA_TOPIC, 1);
    props = getSinkConnectorProps();
    if (connection == null) {
      TestUtils.waitForCondition(
          () -> assertDbConnection(mySqlContainer.getMappedPort(3306)).orElse(false),
          TimeUnit.SECONDS.toMillis(50),
          "Failed to start the container.");
    }
    System.out.println("Started cluster");
    dropTableIfExists(KAFKA_TOPIC);
  }

  @After
  public void close() {
    // delete connector
    connect.deleteConnector(CONNECTOR_NAME);
    connect.stop();
  }

  @AfterClass
  public static void closeConnection() {
    mySqlContainer.close();
  }

  @Test
  public void testSuccess() throws Exception {
    sendTestDataToKafka(0, NUM_RECORDS);
    ConsumerRecords<byte[], byte[]> totalRecords = connect.kafka().consume(
        NUM_RECORDS,
        CONSUME_MAX_DURATION_MS,
        KAFKA_TOPIC);
    log.info("Number of records added in kafka {}", totalRecords.count());

    // Configure Connector and wait some specific time to start the connector.
    connect.configureConnector(CONNECTOR_NAME, props);
    waitForConnectorToStart(CONNECTOR_NAME, Integer.valueOf(MAX_TASKS));

    // Wait Connector to write data into Mysql
    waitForConnectorToWriteDataIntoMysql(
        connection,
        CONNECTOR_NAME,
        Integer.valueOf(MAX_TASKS),
        KAFKA_TOPIC,
        NUM_RECORDS);
    assertRecordsCountAndContent(NUM_RECORDS);
  }

  @Test
  public void testSchemaEvolution() throws Exception {
    //Sending Data to kafka
    sendTestDataToKafka(0, NUM_RECORDS);
    log.info("Data sent to kafka");

    //Consuming the sent records
    ConsumerRecords<byte[], byte[]> totalRecords = connect.kafka().consume(
            NUM_RECORDS,
            CONSUME_MAX_DURATION_MS,
            KAFKA_TOPIC);
    log.info("Number of records added in kafka {}", totalRecords.count());

    // Configure Connector and wait some specific time to start the connector
    connect.configureConnector(CONNECTOR_NAME, props);
    waitForConnectorToStart(CONNECTOR_NAME, Integer.valueOf(MAX_TASKS));

    // Wait Connector to write data into Mysql
    waitForConnectorToWriteDataIntoMysql(
            connection,
            CONNECTOR_NAME,
            Integer.valueOf(MAX_TASKS),
            KAFKA_TOPIC,
            NUM_RECORDS);

    //Checking if all the records have been sent to Mysql or not
    assertRecordsCountAndContent(NUM_RECORDS);
    log.info("Data sent to database");

    //Sending data to kafka withe new schema
    sendTestDataToKafkaWithNewSchema(NUM_RECORDS, NUM_RECORDS);
    log.info("Data with new schema sent to kafka");

    //Consuming the sent records
    ConsumerRecords<byte[], byte[]> totalRecordsNew = connect.kafka().consume(
            NUM_RECORDS,
            CONSUME_MAX_DURATION_MS,
            KAFKA_TOPIC);
    log.info("Number of records with new schema added in kafka {}", totalRecordsNew.count());

    // Wait Connector to write data into Mysql
    waitForConnectorToWriteDataIntoMysql(
            connection,
            CONNECTOR_NAME,
            Integer.valueOf(MAX_TASKS),
            KAFKA_TOPIC,
            NUM_RECORDS*2);

    //Checking if all the records with the new schema has been sent to Mysql or not
    assertRecordsCountAndContentForNewSchema(NUM_RECORDS*2);
  }

  private void assertRecordsCountAndContent(int recordCount) throws SQLException {
    Statement st = connection.createStatement();
    ResultSet rs = st.executeQuery("SELECT * FROM " + KAFKA_TOPIC);
    int counter = 0;
    while (rs.next()) {
      Assert.assertEquals(counter++, rs.getInt("userId"));
      Assert.assertEquals("Alex", rs.getString("firstName"));
      Assert.assertEquals("Smith", rs.getString("lastName"));
      Assert.assertEquals(20, rs.getInt("age"));
    }
    Assert.assertEquals(counter, recordCount);
  }

  private void assertRecordsCountAndContentForNewSchema(int recordCount) throws SQLException {
    Statement st = connection.createStatement();
    ResultSet rs = st.executeQuery("SELECT * FROM " + KAFKA_TOPIC);
    int counter = 0;
    while (counter < recordCount/2 && rs.next())
    {
      Assert.assertEquals(counter++, rs.getInt("userId"));
      Assert.assertEquals("Alex", rs.getString("firstName"));
      Assert.assertEquals("Smith", rs.getString("lastName"));
      Assert.assertEquals(20, rs.getInt("age"));
    }
    while (rs.next()) {
      Assert.assertEquals(counter++, rs.getInt("userId"));
      Assert.assertEquals("Alex", rs.getString("firstName"));
      Assert.assertEquals("Smith", rs.getString("lastName"));
    }
    Assert.assertEquals(counter, recordCount);
  }

  private void sendTestDataToKafka(int startIndex, int numRecords) throws InterruptedException {
    for (int i = startIndex; i < startIndex + numRecords; i++) {
      String value = getTestKafkaRecord(KAFKA_TOPIC, SCHEMA, i);
      connect.kafka().produce(KAFKA_TOPIC, null, value);
    }
  }
  private void sendTestDataToKafkaWithNewSchema(int startIndex, int numRecords) throws InterruptedException {
    for (int i = startIndex; i < startIndex + numRecords; i++) {
      String value = getTestKafkaRecordWithNewSchema(KAFKA_TOPIC, NEW_SCHEMA, i);
      connect.kafka().produce(KAFKA_TOPIC, null, value);
    }
  }
  /**
   * Create a map of Common connector properties.
   *
   * @return : Map of props.
   */
  private Map<String, String> getSinkConnectorProps() {
    Map<String, String> props = new HashMap<>();
    props.put(SinkConnectorConfig.TOPICS_CONFIG, KAFKA_TOPIC);
    props.put("connector.class", JdbcSinkConnector.class.getName());
    props.put("tasks.max", MAX_TASKS);
    // license properties
    props.put("confluent.topic.replication.factor", "1");
    props.put("confluent.topic.bootstrap.servers", connect.kafka().bootstrapServers());
    // connector-specific properties
    props.put("connection.url", getConnectionUrl(mySqlContainer.getMappedPort(3306)));
    props.put("connection.user", "user");
    props.put("connection.password", "password");
    props.put("dialect.name", "MySqlDatabaseDialect");
    props.put("auto.create", "true");
    props.put("auto.evolve", "true");
    props.put("value.converter", JsonConverter.class.getName());
    return props;
  }

  private String getTestKafkaRecord(String topic, Schema schema, int i) {
      final Struct struct = new Struct(schema)
          .put("userId", i)
          .put("firstName", "Alex")
          .put("lastName", "Smith")
          .put("age", 20);
      JsonConverter jsonConverter = new JsonConverter();
      Map<String, String> config = new HashMap<>();
      config.put(JsonConverterConfig.SCHEMAS_CACHE_SIZE_CONFIG, "100");
      config.put(ConverterConfig.TYPE_CONFIG, ConverterType.VALUE.getName());
      config.put(JsonConverterConfig.SCHEMAS_ENABLE_CONFIG, "true");
      jsonConverter.configure(config);
      byte[] raw = jsonConverter.fromConnectData(topic, schema, struct);
      return new String(raw, StandardCharsets.UTF_8);
    }

  private String getTestKafkaRecordWithNewSchema(String topic, Schema schema, int i) {
    final Struct struct = new Struct(schema)
            .put("userId", i)
            .put("firstName", "Alex")
            .put("lastName", "Smith");
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, String> config = new HashMap<>();
    config.put(JsonConverterConfig.SCHEMAS_CACHE_SIZE_CONFIG, "100");
    config.put(ConverterConfig.TYPE_CONFIG, ConverterType.VALUE.getName());
    config.put(JsonConverterConfig.SCHEMAS_ENABLE_CONFIG, "true");
    jsonConverter.configure(config);
    byte[] raw = jsonConverter.fromConnectData(topic, schema, struct);
    return new String(raw, StandardCharsets.UTF_8);
  }
}