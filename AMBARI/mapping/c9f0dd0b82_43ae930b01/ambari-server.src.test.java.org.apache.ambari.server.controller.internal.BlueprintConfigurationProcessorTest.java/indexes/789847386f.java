/**
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

package org.apache.ambari.server.controller.internal;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.topology.Blueprint;
import org.apache.ambari.server.topology.Cardinality;
import org.apache.ambari.server.topology.ClusterTopology;
import org.apache.ambari.server.topology.ClusterTopologyImpl;
import org.apache.ambari.server.topology.Configuration;
import org.apache.ambari.server.topology.HostGroup;
import org.apache.ambari.server.topology.HostGroupImpl;
import org.apache.ambari.server.topology.HostGroupInfo;
import org.apache.ambari.server.topology.InvalidTopologyException;
import org.apache.commons.collections.map.HashedMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * BlueprintConfigurationProcessor unit tests.
 */
public class BlueprintConfigurationProcessorTest {

  private static final Configuration EMPTY_CONFIG = new Configuration(Collections.<String, Map<String, String>>emptyMap(),
      Collections.<String, Map<String, Map<String, String>>>emptyMap());

  private final Map<String, Collection<String>> serviceComponents = new HashMap<String, Collection<String>>();

  private final Blueprint bp = createNiceMock(Blueprint.class);
  //private final AmbariMetaInfo metaInfo = createNiceMock(AmbariMetaInfo.class);
  private final ServiceInfo serviceInfo = createNiceMock(ServiceInfo.class);
  private final Stack stack = createNiceMock(Stack.class);

  @Before
  public void init() throws Exception {
    expect(bp.getStack()).andReturn(stack).anyTimes();
    expect(bp.getName()).andReturn("test-bp").anyTimes();

    expect(stack.getName()).andReturn("testStack").anyTimes();
    expect(stack.getVersion()).andReturn("1").anyTimes();
    // return false for all components since for this test we don't care about the value
    expect(stack.isMasterComponent((String) anyObject())).andReturn(false).anyTimes();

    expect(serviceInfo.getRequiredProperties()).andReturn(
        Collections.<String, org.apache.ambari.server.state.PropertyInfo>emptyMap()).anyTimes();
    expect(serviceInfo.getRequiredServices()).andReturn(Collections.<String>emptyList()).anyTimes();

    Collection<String> hdfsComponents = new HashSet<String>();
    hdfsComponents.add("NAMENODE");
    hdfsComponents.add("SECONDARY_NAMENODE");
    hdfsComponents.add("DATANODE");
    hdfsComponents.add("HDFS_CLIENT");
    serviceComponents.put("HDFS", hdfsComponents);

    Collection<String> yarnComponents = new HashSet<String>();
    yarnComponents.add("RESOURCEMANAGER");
    yarnComponents.add("NODEMANAGER");
    yarnComponents.add("YARN_CLIENT");
    yarnComponents.add("APP_TIMELINE_SERVER");
    serviceComponents.put("YARN", yarnComponents);

    Collection<String> mrComponents = new HashSet<String>();
    mrComponents.add("MAPREDUCE2_CLIENT");
    mrComponents.add("HISTORY_SERVER");
    serviceComponents.put("MAPREDUCE2", mrComponents);

    Collection<String> zkComponents = new HashSet<String>();
    zkComponents.add("ZOOKEEPER_SERVER");
    zkComponents.add("ZOOKEEPER_CLIENT");
    serviceComponents.put("ZOOKEEPER", zkComponents);

    Collection<String> hiveComponents = new HashSet<String>();
    hiveComponents.add("MYSQL_SERVER");
    hiveComponents.add("HIVE_METASTORE");
    serviceComponents.put("HIVE", hiveComponents);

    Collection<String> falconComponents = new HashSet<String>();
    falconComponents.add("FALCON_SERVER");
    falconComponents.add("FALCON_CLIENT");
    serviceComponents.put("FALCON", falconComponents);

    Collection<String> gangliaComponents = new HashSet<String>();
    gangliaComponents.add("GANGLIA_SERVER");
    gangliaComponents.add("GANGLIA_CLIENT");
    serviceComponents.put("GANGLIA", gangliaComponents);

    Collection<String> kafkaComponents = new HashSet<String>();
    kafkaComponents.add("KAFKA_BROKER");
    serviceComponents.put("KAFKA", kafkaComponents);

    Collection<String> knoxComponents = new HashSet<String>();
    knoxComponents.add("KNOX_GATEWAY");
    serviceComponents.put("KNOX", knoxComponents);

    Collection<String> oozieComponents = new HashSet<String>();
    oozieComponents.add("OOZIE_SERVER");
    oozieComponents.add("OOZIE_CLIENT");
    serviceComponents.put("OOZIE", oozieComponents);

    for (Map.Entry<String, Collection<String>> entry : serviceComponents.entrySet()) {
      String service = entry.getKey();
      for (String component : entry.getValue()) {
        expect(stack.getServiceForComponent(component)).andReturn(service).anyTimes();
      }
    }

    expect(stack.getCardinality("MYSQL_SERVER")).andReturn(new Cardinality("0-1")).anyTimes();
  }

  @After
  public void tearDown() {
    reset(bp, serviceInfo, stack);
  }

  @Test
  public void testDoUpdateForBlueprintExport_SingleHostProperty() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "testhost");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("yarn-site").get("yarn.resourcemanager.hostname");
    assertEquals("%HOSTGROUP::group1%", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_SingleHostProperty__withPort() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("fs.defaultFS", "testhost:8020");
    properties.put("core-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("core-site").get("fs.defaultFS");
    assertEquals("%HOSTGROUP::group1%:8020", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_SingleHostProperty__ExternalReference() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "external-host");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertFalse(properties.get("yarn-site").containsKey("yarn.resourcemanager.hostname"));
  }

  @Test
  public void testDoUpdateForBlueprintExport_MultiHostProperty() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("hbase.zookeeper.quorum", "testhost,testhost2,testhost2a,testhost2b");
    properties.put("hbase-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("hbase-site").get("hbase.zookeeper.quorum");
    assertEquals("%HOSTGROUP::group1%,%HOSTGROUP::group2%", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_MultiHostProperty__WithPorts() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("templeton.zookeeper.hosts", "testhost:5050,testhost2:9090,testhost2a:9090,testhost2b:9090");
    properties.put("webhcat-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("webhcat-site").get("templeton.zookeeper.hosts");
    assertEquals("%HOSTGROUP::group1%:5050,%HOSTGROUP::group2%:9090", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_MultiHostProperty__YAML() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("storm.zookeeper.servers", "['testhost:5050','testhost2:9090','testhost2a:9090','testhost2b:9090']");
    properties.put("storm-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("storm-site").get("storm.zookeeper.servers");
    assertEquals("['%HOSTGROUP::group1%:5050','%HOSTGROUP::group2%:9090']", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_DBHostProperty() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hiveSiteProps = new HashMap<String, String>();
    hiveSiteProps.put("javax.jdo.option.ConnectionURL", "jdbc:mysql://testhost/hive?createDatabaseIfNotExist=true");
    properties.put("hive-site", hiveSiteProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    hgComponents.add("MYSQL_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    String updatedVal = properties.get("hive-site").get("javax.jdo.option.ConnectionURL");
    assertEquals("jdbc:mysql://%HOSTGROUP::group1%/hive?createDatabaseIfNotExist=true", updatedVal);
  }

  @Test
  public void testDoUpdateForBlueprintExport_DBHostProperty__External() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("javax.jdo.option.ConnectionURL", "jdbc:mysql://external-host/hive?createDatabaseIfNotExist=true");
    properties.put("hive-site", typeProps);

    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertFalse(properties.get("hive-site").containsKey("javax.jdo.option.ConnectionURL"));
  }

  @Test
  public void testFalconConfigExport() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> falconStartupProperties = new HashMap<String, String>();
    configProperties.put("falcon-startup.properties", falconStartupProperties);

    // setup properties that include host information
    falconStartupProperties.put("*.broker.url", expectedHostName + ":" + expectedPortNum);
    falconStartupProperties.put("*.falcon.service.authentication.kerberos.principal", "falcon/" + expectedHostName + "@EXAMPLE.COM");
    falconStartupProperties.put("*.falcon.http.authentication.kerberos.principal", "HTTP/" + expectedHostName + "@EXAMPLE.COM");

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("FALCON_SERVER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Falcon Broker URL property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), falconStartupProperties.get("*.broker.url"));

    assertEquals("Falcon Kerberos Principal property not properly exported",
        "falcon/" + "%HOSTGROUP::" + expectedHostGroupName + "%" + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.service.authentication.kerberos.principal"));

    assertEquals("Falcon Kerberos HTTP Principal property not properly exported",
        "HTTP/" + "%HOSTGROUP::" + expectedHostGroupName + "%" + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.http.authentication.kerberos.principal"));
  }

  @Test
  public void testDoNameNodeHighAvailabilityExportWithHAEnabled() throws Exception {
    final String expectedNameService = "mynameservice";
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedNodeOne = "nn1";
    final String expectedNodeTwo = "nn2";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();

    configProperties.put("hdfs-site", hdfsSiteProperties);
    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("hbase-site", hbaseSiteProperties);

    // setup hdfs config for test
    hdfsSiteProperties.put("dfs.nameservices", expectedNameService);
    hdfsSiteProperties.put("dfs.ha.namenodes.mynameservice", expectedNodeOne + ", " + expectedNodeTwo);

    // setup properties that include host information
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne, expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo, expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne, expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo, expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne, expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo, expectedHostName + ":" + expectedPortNum);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("NAMENODE");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo));
  }

  @Test
  public void testDoNameNodeHighAvailabilityExportWithHAEnabledNameServicePropertiesIncluded() throws Exception {
    final String expectedNameService = "mynameservice";
    final String expectedHostName = "c6401.apache.ambari.org";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();
    Map<String, String> accumuloSiteProperties = new HashMap<String, String>();

    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("hbase-site", hbaseSiteProperties);
    configProperties.put("accumulo-site", accumuloSiteProperties);

    // configure fs.defaultFS to include a nameservice name, rather than a host name
    coreSiteProperties.put("fs.defaultFS", "hdfs://" + expectedNameService);
    // configure hbase.rootdir to include a nameservice name, rather than a host name
    hbaseSiteProperties.put("hbase.rootdir", "hdfs://" + expectedNameService + "/apps/hbase/data");
    // configure instance.volumes to include a nameservice name, rather than a host name
    accumuloSiteProperties.put("instance.volumes", "hdfs://" + expectedNameService + "/apps/accumulo/data");

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("RESOURCEMANAGER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup("group1", groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    // verify that any properties that include nameservices are not removed from the exported blueprint's configuration
    assertEquals("Property containing an HA nameservice (fs.defaultFS), was not correctly exported by the processor",
        "hdfs://" + expectedNameService, coreSiteProperties.get("fs.defaultFS"));
    assertEquals("Property containing an HA nameservice (hbase.rootdir), was not correctly exported by the processor",
        "hdfs://" + expectedNameService + "/apps/hbase/data", hbaseSiteProperties.get("hbase.rootdir"));
    assertEquals("Property containing an HA nameservice (instance.volumes), was not correctly exported by the processor",
        "hdfs://" + expectedNameService + "/apps/accumulo/data", accumuloSiteProperties.get("instance.volumes"));
  }

  @Test
  public void testDoNameNodeHighAvailabilityExportWithHANotEnabled() throws Exception {
    // hdfs-site config for this test will not include an HA values
    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    configProperties.put("hdfs-site", hdfsSiteProperties);

    assertEquals("Incorrect initial state for hdfs-site config",
        0, hdfsSiteProperties.size());

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("NAMENODE");
    TestHostGroup group = new TestHostGroup("group1", groupComponents, Collections.singleton("host1"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Incorrect state for hdfs-site config after HA call in non-HA environment, should be zero",
        0, hdfsSiteProperties.size());
  }

  @Test
  public void testDoNameNodeHighAvailabilityExportWithHAEnabledMultipleServices() throws Exception {
    final String expectedNameServiceOne = "mynameserviceOne";
    final String expectedNameServiceTwo = "mynameserviceTwo";
    final String expectedHostNameOne = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.apache.ambari.org";

    final String expectedPortNum = "808080";
    final String expectedNodeOne = "nn1";
    final String expectedNodeTwo = "nn2";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    configProperties.put("hdfs-site", hdfsSiteProperties);

    // setup hdfs config for test
    hdfsSiteProperties.put("dfs.nameservices", expectedNameServiceOne + "," + expectedNameServiceTwo);
    hdfsSiteProperties.put("dfs.ha.namenodes." + expectedNameServiceOne, expectedNodeOne + ", " + expectedNodeTwo);
    hdfsSiteProperties.put("dfs.ha.namenodes." + expectedNameServiceTwo, expectedNodeOne + ", " + expectedNodeTwo);

    // setup properties that include host information for nameservice one
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameServiceOne + "." + expectedNodeOne, expectedHostNameOne + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameServiceOne + "." + expectedNodeTwo, expectedHostNameOne + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameServiceOne + "." + expectedNodeOne, expectedHostNameOne + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameServiceOne + "." + expectedNodeTwo, expectedHostNameOne + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameServiceOne + "." + expectedNodeOne, expectedHostNameOne + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameServiceOne + "." + expectedNodeTwo, expectedHostNameOne + ":" + expectedPortNum);

    // setup properties that include host information for nameservice two
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameServiceTwo + "." + expectedNodeOne, expectedHostNameTwo + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameServiceTwo + "." + expectedNodeTwo, expectedHostNameTwo + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameServiceTwo + "." + expectedNodeOne, expectedHostNameTwo + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameServiceTwo + "." + expectedNodeTwo, expectedHostNameTwo + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameServiceTwo + "." + expectedNodeOne, expectedHostNameTwo + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameServiceTwo + "." + expectedNodeTwo, expectedHostNameTwo + ":" + expectedPortNum);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("RESOURCEMANAGER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostNameOne);
    hosts.add(expectedHostNameTwo);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    // verify results for name service one
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameServiceOne + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameServiceOne + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameServiceOne + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameServiceOne + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameServiceOne + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameServiceOne + "." + expectedNodeTwo));


    // verify results for name service two
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameServiceTwo + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameServiceTwo + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameServiceTwo + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameServiceTwo + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameServiceTwo + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameServiceTwo + "." + expectedNodeTwo));
  }

  @Test
  public void testYarnConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> yarnSiteProperties = new HashMap<String, String>();
    configProperties.put("yarn-site", yarnSiteProperties);

    // setup properties that include host information
    yarnSiteProperties.put("yarn.log.server.url", "http://" + expectedHostName +":19888/jobhistory/logs");
    yarnSiteProperties.put("yarn.resourcemanager.hostname", expectedHostName);
    yarnSiteProperties.put("yarn.resourcemanager.resource-tracker.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.webapp.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.scheduler.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.admin.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.https.address", expectedHostName + ":" + expectedPortNum);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("RESOURCEMANAGER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Yarn Log Server URL was incorrectly exported",
        "http://" + "%HOSTGROUP::" + expectedHostGroupName + "%" +":19888/jobhistory/logs", yarnSiteProperties.get("yarn.log.server.url"));
    assertEquals("Yarn ResourceManager hostname was incorrectly exported",
        createExportedHostName(expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.hostname"));
    assertEquals("Yarn ResourceManager tracker address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.resource-tracker.address"));
    assertEquals("Yarn ResourceManager webapp address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.webapp.address"));
    assertEquals("Yarn ResourceManager scheduler address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.scheduler.address"));
    assertEquals("Yarn ResourceManager address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.address"));
    assertEquals("Yarn ResourceManager admin address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.admin.address"));
    assertEquals("Yarn ResourceManager timeline-service address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.timeline-service.address"));
    assertEquals("Yarn ResourceManager timeline webapp address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.timeline-service.webapp.address"));
    assertEquals("Yarn ResourceManager timeline webapp HTTPS address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.timeline-service.webapp.https.address"));
  }

  @Test
  public void testYarnConfigExportedWithDefaultZeroHostAddress() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> yarnSiteProperties = new HashMap<String, String>();
    configProperties.put("yarn-site", yarnSiteProperties);

    // setup properties that include host information
    yarnSiteProperties.put("yarn.log.server.url", "http://" + expectedHostName +":19888/jobhistory/logs");
    yarnSiteProperties.put("yarn.resourcemanager.hostname", expectedHostName);
    yarnSiteProperties.put("yarn.resourcemanager.resource-tracker.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.webapp.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.scheduler.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.admin.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.address", "0.0.0.0" + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.address", "0.0.0.0" + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.https.address", "0.0.0.0" + ":" + expectedPortNum);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("RESOURCEMANAGER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Yarn Log Server URL was incorrectly exported",
        "http://" + "%HOSTGROUP::" + expectedHostGroupName + "%" +":19888/jobhistory/logs", yarnSiteProperties.get("yarn.log.server.url"));
    assertEquals("Yarn ResourceManager hostname was incorrectly exported",
        createExportedHostName(expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.hostname"));
    assertEquals("Yarn ResourceManager tracker address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.resource-tracker.address"));
    assertEquals("Yarn ResourceManager webapp address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.webapp.address"));
    assertEquals("Yarn ResourceManager scheduler address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.scheduler.address"));
    assertEquals("Yarn ResourceManager address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.address"));
    assertEquals("Yarn ResourceManager admin address was incorrectly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), yarnSiteProperties.get("yarn.resourcemanager.admin.address"));
    assertEquals("Yarn ResourceManager timeline-service address was incorrectly exported",
        "0.0.0.0" + ":" + expectedPortNum, yarnSiteProperties.get("yarn.timeline-service.address"));
    assertEquals("Yarn ResourceManager timeline webapp address was incorrectly exported",
        "0.0.0.0" + ":" + expectedPortNum, yarnSiteProperties.get("yarn.timeline-service.webapp.address"));
    assertEquals("Yarn ResourceManager timeline webapp HTTPS address was incorrectly exported",
        "0.0.0.0" + ":" + expectedPortNum, yarnSiteProperties.get("yarn.timeline-service.webapp.https.address"));
  }

  @Test
  public void testHDFSConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();
    Map<String, String> accumuloSiteProperties = new HashMap<String, String>();

    configProperties.put("hdfs-site", hdfsSiteProperties);
    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("hbase-site", hbaseSiteProperties);
    configProperties.put("accumulo-site", accumuloSiteProperties);

    // setup properties that include host information
    hdfsSiteProperties.put("dfs.http.address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.https.address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.http-address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.https-address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.secondary.http.address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.secondary.http-address", expectedHostName + ":" + expectedPortNum);
    hdfsSiteProperties.put("dfs.namenode.shared.edits.dir", expectedHostName + ":" + expectedPortNum);

    coreSiteProperties.put("fs.default.name", expectedHostName + ":" + expectedPortNum);
    coreSiteProperties.put("fs.defaultFS", "hdfs://" + expectedHostName + ":" + expectedPortNum);

    hbaseSiteProperties.put("hbase.rootdir", "hdfs://" + expectedHostName + ":" + expectedPortNum + "/apps/hbase/data");

    accumuloSiteProperties.put("instance.volumes", "hdfs://" + expectedHostName + ":" + expectedPortNum + "/apps/accumulo/data");

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("NAMENODE");
    groupComponents.add("SECONDARY_NAMENODE");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.http.address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.https.address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.http-address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.https-address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.secondary.http.address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.secondary.http-address"));
    assertEquals("hdfs config property not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hdfsSiteProperties.get("dfs.namenode.shared.edits.dir"));

    assertEquals("hdfs config in core-site not exported properly",
        createExportedAddress(expectedPortNum, expectedHostGroupName), coreSiteProperties.get("fs.default.name"));
    assertEquals("hdfs config in core-site not exported properly",
        "hdfs://" + createExportedAddress(expectedPortNum, expectedHostGroupName), coreSiteProperties.get("fs.defaultFS"));

    assertEquals("hdfs config in hbase-site not exported properly",
        "hdfs://" + createExportedAddress(expectedPortNum, expectedHostGroupName) + "/apps/hbase/data", hbaseSiteProperties.get("hbase.rootdir"));

    assertEquals("hdfs config in accumulo-site not exported properly",
        "hdfs://" + createExportedAddress(expectedPortNum, expectedHostGroupName) + "/apps/accumulo/data", accumuloSiteProperties.get("instance.volumes"));
  }

  @Test
  public void testHiveConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hiveSiteProperties = new HashMap<String, String>();
    Map<String, String> hiveEnvProperties = new HashMap<String, String>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();

    configProperties.put("hive-site", hiveSiteProperties);
    configProperties.put("hive-env", hiveEnvProperties);
    configProperties.put("webhcat-site", webHCatSiteProperties);
    configProperties.put("core-site", coreSiteProperties);

    // setup properties that include host information
    hiveSiteProperties.put("hive.metastore.uris", "thrift://" + expectedHostName + ":" + expectedPortNum);
    hiveSiteProperties.put("javax.jdo.option.ConnectionURL", expectedHostName + ":" + expectedPortNum);
    hiveSiteProperties.put("hive.zookeeper.quorum", expectedHostName + ":" + expectedPortNum + "," + expectedHostNameTwo + ":" + expectedPortNum);
    hiveSiteProperties.put("hive.cluster.delegation.token.store.zookeeper.connectString", expectedHostName + ":" + expectedPortNum + "," + expectedHostNameTwo + ":" + expectedPortNum);
    hiveEnvProperties.put("hive_hostname", expectedHostName);

    webHCatSiteProperties.put("templeton.hive.properties", expectedHostName + "," + expectedHostNameTwo);
    webHCatSiteProperties.put("templeton.kerberos.principal", expectedHostName);

    coreSiteProperties.put("hadoop.proxyuser.hive.hosts", expectedHostName + "," + expectedHostNameTwo);
    coreSiteProperties.put("hadoop.proxyuser.HTTP.hosts", expectedHostName + "," + expectedHostNameTwo);
    coreSiteProperties.put("hadoop.proxyuser.hcat.hosts", expectedHostName + "," + expectedHostNameTwo);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("HIVE_SERVER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("HIVE_CLIENT");
    Collection<String> hosts2 = new ArrayList<String>();
    hosts2.add(expectedHostNameTwo);
    hosts2.add("serverFour");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, groupComponents2, hosts2);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("hive property not properly exported",
        "thrift://" + createExportedAddress(expectedPortNum, expectedHostGroupName), hiveSiteProperties.get("hive.metastore.uris"));
    assertEquals("hive property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hiveSiteProperties.get("javax.jdo.option.ConnectionURL"));
    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName), hiveEnvProperties.get("hive_hostname"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        webHCatSiteProperties.get("templeton.hive.properties"));
    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName), webHCatSiteProperties.get("templeton.kerberos.principal"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.hive.hosts"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.HTTP.hosts"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.hcat.hosts"));

    assertEquals("hive zookeeper quorum property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName) + "," + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo),
        hiveSiteProperties.get("hive.zookeeper.quorum"));

    assertEquals("hive zookeeper connectString property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName) + "," + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo),
        hiveSiteProperties.get("hive.cluster.delegation.token.store.zookeeper.connectString"));

  }

  @Test
  public void testHiveConfigExportedMultipleHiveMetaStoreServers() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties =
        new HashMap<String, Map<String, String>>();

    Map<String, String> hiveSiteProperties =
        new HashMap<String, String>();
    Map<String, String> hiveEnvProperties =
        new HashMap<String, String>();
    Map<String, String> webHCatSiteProperties =
        new HashMap<String, String>();
    Map<String, String> coreSiteProperties =
        new HashMap<String, String>();

    configProperties.put("hive-site", hiveSiteProperties);
    configProperties.put("hive-env", hiveEnvProperties);
    configProperties.put("webhcat-site", webHCatSiteProperties);
    configProperties.put("core-site", coreSiteProperties);

    // setup properties that include host information
    hiveSiteProperties.put("hive.metastore.uris", "thrift://" + expectedHostName + ":" + expectedPortNum + "," + "thrift://" + expectedHostNameTwo + ":" + expectedPortNum);
    hiveSiteProperties.put("javax.jdo.option.ConnectionURL", expectedHostName + ":" + expectedPortNum);
    hiveSiteProperties.put("hive.zookeeper.quorum", expectedHostName + ":" + expectedPortNum + "," + expectedHostNameTwo + ":" + expectedPortNum);
    hiveSiteProperties.put("hive.cluster.delegation.token.store.zookeeper.connectString", expectedHostName + ":" + expectedPortNum + "," + expectedHostNameTwo + ":" + expectedPortNum);
    hiveEnvProperties.put("hive_hostname", expectedHostName);

    webHCatSiteProperties.put("templeton.hive.properties", expectedHostName + "," + expectedHostNameTwo);
    webHCatSiteProperties.put("templeton.kerberos.principal", expectedHostName);

    coreSiteProperties.put("hadoop.proxyuser.hive.hosts", expectedHostName + "," + expectedHostNameTwo);
    coreSiteProperties.put("hadoop.proxyuser.HTTP.hosts", expectedHostName + "," + expectedHostNameTwo);
    coreSiteProperties.put("hadoop.proxyuser.hcat.hosts", expectedHostName + "," + expectedHostNameTwo);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("NAMENODE");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("DATANODE");
    Collection<String> hosts2 = new ArrayList<String>();
    hosts2.add(expectedHostNameTwo);
    hosts2.add("serverThree");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, groupComponents2, hosts2);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    System.out.println("RWN: exported value of hive.metastore.uris = " + hiveSiteProperties.get("hive.metastore.uris"));

    assertEquals("hive property not properly exported",
        "thrift://" + createExportedAddress(expectedPortNum, expectedHostGroupName) + "," + "thrift://" + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo), hiveSiteProperties.get("hive.metastore.uris"));
    assertEquals("hive property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName), hiveSiteProperties.get("javax.jdo.option.ConnectionURL"));
    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName), hiveEnvProperties.get("hive_hostname"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        webHCatSiteProperties.get("templeton.hive.properties"));
    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName), webHCatSiteProperties.get("templeton.kerberos.principal"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.hive.hosts"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.HTTP.hosts"));

    assertEquals("hive property not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.hcat.hosts"));

    assertEquals("hive zookeeper quorum property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName) + "," + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo),
        hiveSiteProperties.get("hive.zookeeper.quorum"));

    assertEquals("hive zookeeper connectString property not properly exported",
        createExportedAddress(expectedPortNum, expectedHostGroupName) + "," + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo),
        hiveSiteProperties.get("hive.cluster.delegation.token.store.zookeeper.connectString"));
  }

  @Test
  public void testOozieConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedExternalHost = "c6408.ambari.apache.org";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> oozieSiteProperties = new HashMap<String, String>();
    Map<String, String> oozieEnvProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();

    configProperties.put("oozie-site", oozieSiteProperties);
    configProperties.put("oozie-env", oozieEnvProperties);
    configProperties.put("hive-env", oozieEnvProperties);
    configProperties.put("core-site", coreSiteProperties);

    oozieSiteProperties.put("oozie.base.url", expectedHostName);
    oozieSiteProperties.put("oozie.authentication.kerberos.principal", expectedHostName);
    oozieSiteProperties.put("oozie.service.HadoopAccessorService.kerberos.principal", expectedHostName);
    oozieSiteProperties.put("oozie.service.JPAService.jdbc.url", "jdbc:mysql://" + expectedExternalHost + "/ooziedb");

    oozieEnvProperties.put("oozie_hostname", expectedHostName);
    oozieEnvProperties.put("oozie_existing_mysql_host", expectedExternalHost);

    coreSiteProperties.put("hadoop.proxyuser.oozie.hosts", expectedHostName + "," + expectedHostNameTwo);

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // note: test hostgroups may not accurately reflect the required components for the config properties
    // which are mapped to them.  Only the hostgroup name is used for hostgroup resolution an the components
    // are not validated
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("OOZIE_SERVER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("OOZIE_SERVER");
    Collection<String> hosts2 = new ArrayList<String>();
    hosts2.add(expectedHostNameTwo);
    hosts2.add("serverFour");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, groupComponents2, hosts2);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("oozie property not exported correctly",
        createExportedHostName(expectedHostGroupName), oozieSiteProperties.get("oozie.base.url"));
    assertEquals("oozie property not exported correctly",
        createExportedHostName(expectedHostGroupName), oozieSiteProperties.get("oozie.authentication.kerberos.principal"));
    assertEquals("oozie property not exported correctly",
        createExportedHostName(expectedHostGroupName), oozieSiteProperties.get("oozie.service.HadoopAccessorService.kerberos.principal"));
    assertEquals("oozie property not exported correctly",
        createExportedHostName(expectedHostGroupName), oozieEnvProperties.get("oozie_hostname"));
    assertEquals("oozie property not exported correctly",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo), coreSiteProperties.get("hadoop.proxyuser.oozie.hosts"));

    // verify that the oozie properties that can refer to an external DB are not included in the export
    assertFalse("oozie_existing_mysql_host should not have been present in the exported configuration",
        oozieEnvProperties.containsKey("oozie_existing_mysql_host"));
    assertFalse("oozie.service.JPAService.jdbc.url should not have been present in the exported configuration",
        oozieSiteProperties.containsKey("oozie.service.JPAService.jdbc.url"));
  }

  @Test
  public void testZookeeperConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";
    final String expectedPortNumberOne = "2112";
    final String expectedPortNumberTwo = "1221";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    Map<String, String> sliderClientProperties = new HashMap<String, String>();
    Map<String, String> yarnSiteProperties = new HashMap<String, String>();
    Map<String, String> kafkaBrokerProperties = new HashMap<String, String>();
    Map<String, String> accumuloSiteProperties = new HashMap<String, String>();

    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("hbase-site", hbaseSiteProperties);
    configProperties.put("webhcat-site", webHCatSiteProperties);
    configProperties.put("slider-client", sliderClientProperties);
    configProperties.put("yarn-site", yarnSiteProperties);
    configProperties.put("kafka-broker", kafkaBrokerProperties);
    configProperties.put("accumulo-site", accumuloSiteProperties);

    coreSiteProperties.put("ha.zookeeper.quorum", expectedHostName + "," + expectedHostNameTwo);
    hbaseSiteProperties.put("hbase.zookeeper.quorum", expectedHostName + "," + expectedHostNameTwo);
    webHCatSiteProperties.put("templeton.zookeeper.hosts", expectedHostName + "," + expectedHostNameTwo);
    yarnSiteProperties.put("hadoop.registry.zk.quorum", createHostAddress(expectedHostName, expectedPortNumberOne) + "," + createHostAddress(expectedHostNameTwo, expectedPortNumberTwo));
    sliderClientProperties.put("slider.zookeeper.quorum", createHostAddress(expectedHostName, expectedPortNumberOne) + "," + createHostAddress(expectedHostNameTwo, expectedPortNumberTwo));
    kafkaBrokerProperties.put("zookeeper.connect", createHostAddress(expectedHostName, expectedPortNumberOne) + "," + createHostAddress(expectedHostNameTwo, expectedPortNumberTwo));
    accumuloSiteProperties.put("instance.zookeeper.host", createHostAddress(expectedHostName, expectedPortNumberOne) + "," + createHostAddress(expectedHostNameTwo, expectedPortNumberTwo));

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    // test hostgroups may not accurately reflect the required components for the config properties which are mapped to them
    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("ZOOKEEPER_SERVER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("ZOOKEEPER_SERVER");
    Collection<String> hosts2 = new ArrayList<String>();
    hosts2.add(expectedHostNameTwo);
    hosts2.add("serverFour");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, groupComponents2, hosts2);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        coreSiteProperties.get("ha.zookeeper.quorum"));
    assertEquals("zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        hbaseSiteProperties.get("hbase.zookeeper.quorum"));
    assertEquals("zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        webHCatSiteProperties.get("templeton.zookeeper.hosts"));
    assertEquals("yarn-site zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName, expectedPortNumberOne) + "," + createExportedHostName(expectedHostGroupNameTwo, expectedPortNumberTwo),
        yarnSiteProperties.get("hadoop.registry.zk.quorum"));
    assertEquals("slider-client zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName, expectedPortNumberOne) + "," + createExportedHostName(expectedHostGroupNameTwo, expectedPortNumberTwo),
        sliderClientProperties.get("slider.zookeeper.quorum"));
    assertEquals("kafka zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName, expectedPortNumberOne) + "," + createExportedHostName(expectedHostGroupNameTwo, expectedPortNumberTwo),
        kafkaBrokerProperties.get("zookeeper.connect"));
    assertEquals("accumulo-site zookeeper config not properly exported",
        createExportedHostName(expectedHostGroupName, expectedPortNumberOne) + "," + createExportedHostName(expectedHostGroupNameTwo, expectedPortNumberTwo),
        accumuloSiteProperties.get("instance.zookeeper.host"));
  }

  @Test
  public void testKnoxSecurityConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties =
        new HashMap<String, Map<String, String>>();

    Map<String, String> coreSiteProperties =
        new HashMap<String, String>();
    Map<String, String> webHCatSiteProperties =
        new HashMap<String, String>();
    Map<String, String> oozieSiteProperties =
        new HashMap<String, String>();

    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("webhcat-site", webHCatSiteProperties);
    configProperties.put("oozie-site", oozieSiteProperties);

    coreSiteProperties.put("hadoop.proxyuser.knox.hosts", expectedHostName + "," + expectedHostNameTwo);
    webHCatSiteProperties.put("webhcat.proxyuser.knox.hosts", expectedHostName + "," + expectedHostNameTwo);
    oozieSiteProperties.put("hadoop.proxyuser.knox.hosts", expectedHostName + "," + expectedHostNameTwo);
    oozieSiteProperties.put("oozie.service.ProxyUserService.proxyuser.knox.hosts", expectedHostName + "," + expectedHostNameTwo);

//    multiCoreSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
//    multiWebhcatSiteMap.put("webhcat.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
//    multiOozieSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("KNOX_GATEWAY");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("KNOX_GATEWAY");
    Collection<String> hosts2 = new ArrayList<String>();
    hosts2.add(expectedHostNameTwo);
    hosts2.add("serverFour");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, groupComponents2, hosts2);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Knox for core-site config not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        coreSiteProperties.get("hadoop.proxyuser.knox.hosts"));
    assertEquals("Knox config for WebHCat not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        webHCatSiteProperties.get("webhcat.proxyuser.knox.hosts"));
    assertEquals("Knox config for Oozie not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        oozieSiteProperties.get("hadoop.proxyuser.knox.hosts"));
    assertEquals("Knox config for Oozie not properly exported",
        createExportedHostName(expectedHostGroupName) + "," + createExportedHostName(expectedHostGroupNameTwo),
        oozieSiteProperties.get("oozie.service.ProxyUserService.proxyuser.knox.hosts"));
  }

  @Test
  public void testKafkaConfigExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostGroupName = "host_group_1";
    final String expectedPortNumberOne = "2112";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> kafkaBrokerProperties = new HashMap<String, String>();
    configProperties.put("kafka-broker", kafkaBrokerProperties);
    kafkaBrokerProperties.put("kafka.ganglia.metrics.host", createHostAddress(expectedHostName, expectedPortNumberOne));

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("KAFKA_BROKER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<String> groupComponents2 = new HashSet<String>();
    groupComponents2.add("NAMENODE");
    TestHostGroup group2 = new TestHostGroup("group2", groupComponents2, Collections.singleton("group2Host"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("kafka Ganglia config not properly exported",
        createExportedHostName(expectedHostGroupName, expectedPortNumberOne),
        kafkaBrokerProperties.get("kafka.ganglia.metrics.host"));
  }

  @Test
  public void testPropertyWithUndefinedHostisExported() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();

    Map<String, String> properties = new HashMap<String, String>();
    configProperties.put("storm-site", properties);

    // setup properties that include host information including undefined host properties
    properties.put("storm.zookeeper.servers", expectedHostName);
    properties.put("nimbus.childopts", "undefined");
    properties.put("worker.childopts", "some other info, undefined, more info");

    Configuration clusterConfig = new Configuration(configProperties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> groupComponents = new HashSet<String>();
    groupComponents.add("ZOOKEEPER_SERVER");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, groupComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor configProcessor = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    configProcessor.doUpdateForBlueprintExport();

    assertEquals("Property was incorrectly exported",
        "%HOSTGROUP::" + expectedHostGroupName + "%", properties.get("storm.zookeeper.servers"));
    assertEquals("Property with undefined host was incorrectly exported",
        "undefined", properties.get("nimbus.childopts"));
    assertEquals("Property with undefined host was incorrectly exported",
        "some other info, undefined, more info" , properties.get("worker.childopts"));
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__defaultValue() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "localhost");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> group1Components = new HashSet<String>();
    group1Components.add("NAMENODE");
    group1Components.add("SECONDARY_NAMENODE");
    group1Components.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", group1Components, Collections.singleton("testhost"));

    Collection<String> group2Components = new HashSet<String>();
    group2Components.add("DATANODE");
    group2Components.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", group2Components, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = properties.get("yarn-site").get("yarn.resourcemanager.hostname");
    assertEquals("testhost", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__MissingComponent() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "localhost");
    typeProps.put("yarn.timeline-service.address", "localhost");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> group1Components = new HashSet<String>();
    group1Components.add("NAMENODE");
    group1Components.add("SECONDARY_NAMENODE");
    group1Components.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", group1Components, Collections.singleton("testhost"));

    Collection<String> group2Components = new HashSet<String>();
    group2Components.add("DATANODE");
    group2Components.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", group2Components, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("APP_TIMELINE_SERVER")).andReturn(new Cardinality("1")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    //todo: should throw a checked exception, not the exception expected by the api
    try {
      updater.doUpdateForClusterCreate();
      fail("IllegalArgumentException should have been thrown");
    } catch (IllegalArgumentException illegalArgumentException) {
      // expected exception
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__MultipleMatchingHostGroupsError() throws Exception {

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "localhost");
    typeProps.put("yarn.timeline-service.address", "localhost");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> group1Components = new HashSet<String>();
    group1Components.add("NAMENODE");
    group1Components.add("SECONDARY_NAMENODE");
    group1Components.add("RESOURCEMANAGER");
    group1Components.add("APP_TIMELINE_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", group1Components, Collections.singleton("testhost"));

    Collection<String> group2Components = new HashSet<String>();
    group2Components.add("DATANODE");
    group2Components.add("HDFS_CLIENT");
    group2Components.add("APP_TIMELINE_SERVER");
    TestHostGroup group2 = new TestHostGroup("group2", group2Components, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("APP_TIMELINE_SERVER")).andReturn(new Cardinality("0-1")).anyTimes();


    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    try {
      updater.doUpdateForClusterCreate();
      fail("IllegalArgumentException should have been thrown");
    } catch (IllegalArgumentException illegalArgumentException) {
      // expected exception
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__MissingOptionalComponent() throws Exception {
    final String expectedHostName = "localhost";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.timeline-service.address", expectedHostName);
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> group1Components = new HashSet<String>();
    group1Components.add("NAMENODE");
    group1Components.add("SECONDARY_NAMENODE");
    group1Components.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", group1Components, Collections.singleton("testhost"));

    Collection<String> group2Components = new HashSet<String>();
    group2Components.add("DATANODE");
    group2Components.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", group2Components, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("APP_TIMELINE_SERVER")).andReturn(new Cardinality("0-1")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("yarn-site").get("yarn.timeline-service.address");
    assertEquals("Timeline Server config property should not have been updated", expectedHostName, updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__defaultValue__WithPort() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("fs.defaultFS", "localhost:5050");
    properties.put("core-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("core-site").get("fs.defaultFS");
    assertEquals("testhost:5050", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__defaultValues() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("hbase.zookeeper.quorum", "localhost");
    properties.put("hbase-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hbase-site").get("hbase.zookeeper.quorum");
    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("testhost");
    expectedHosts.add("testhost2");
    expectedHosts.add("testhost2a");
    expectedHosts.add("testhost2b");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__defaultValues___withPorts() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("templeton.zookeeper.hosts", "localhost:9090");
    properties.put("webhcat-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("webhcat-site").get("templeton.zookeeper.hosts");
    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("testhost:9090");
    expectedHosts.add("testhost2:9090");
    expectedHosts.add("testhost2a:9090");
    expectedHosts.add("testhost2b:9090");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterWithNameNodeHAEnabledSpecifyingHostNamesDirectly() throws Exception {
    final String expectedNameService = "mynameservice";
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "serverTwo";
    final String expectedPortNum = "808080";
    final String expectedNodeOne = "nn1";
    final String expectedNodeTwo = "nn2";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();
    Map<String, String> hadoopEnvProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> accumuloSiteProperties = new HashMap<String, String>();

    configProperties.put("hdfs-site", hdfsSiteProperties);
    configProperties.put("hadoop-env", hadoopEnvProperties);
    configProperties.put("core-site", coreSiteProperties);
    configProperties.put("hbase-site", hbaseSiteProperties);
    configProperties.put("accumulo-site", accumuloSiteProperties);

    // setup hdfs HA config for test
    hdfsSiteProperties.put("dfs.nameservices", expectedNameService);
    hdfsSiteProperties.put("dfs.ha.namenodes.mynameservice", expectedNodeOne + ", " + expectedNodeTwo);

    // setup properties that include exported host group information
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne, createHostAddress(expectedHostName, expectedPortNum));
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo, createHostAddress(expectedHostNameTwo, expectedPortNum));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne, createHostAddress(expectedHostName, expectedPortNum));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo, createHostAddress(expectedHostNameTwo, expectedPortNum));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne, createHostAddress(expectedHostName, expectedPortNum));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo, createHostAddress(expectedHostNameTwo, expectedPortNum));

    // add properties that require the SECONDARY_NAMENODE, which
    // is not included in this test
    hdfsSiteProperties.put("dfs.secondary.http.address", "localhost:8080");
    hdfsSiteProperties.put("dfs.namenode.secondary.http-address", "localhost:8080");

    // configure the defaultFS to use the nameservice URL
    coreSiteProperties.put("fs.defaultFS", "hdfs://" + expectedNameService);

    // configure the hbase rootdir to use the nameservice URL
    hbaseSiteProperties.put("hbase.rootdir", "hdfs://" + expectedNameService + "/hbase/test/root/dir");

    // configure the hbase rootdir to use the nameservice URL
    accumuloSiteProperties.put("instance.volumes", "hdfs://" + expectedNameService + "/accumulo/test/instance/volumes");

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton(expectedHostName));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("NAMENODE");
    TestHostGroup group2 = new TestHostGroup("host-group-2", hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("NAMENODE")).andReturn(new Cardinality("1-2")).anyTimes();
    expect(stack.getCardinality("SECONDARY_NAMENODE")).andReturn(new Cardinality("1")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    // verify that the expected hostname was substituted for the host group name in the config
    assertEquals("HTTPS address HA property not properly exported",
        expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        expectedHostNameTwo + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        expectedHostNameTwo + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
        expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
        expectedHostNameTwo + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo));

    // verify that the Blueprint config processor has set the internal required properties
    // that determine the active and standby node hostnames for this HA setup
    // one of the two hosts should be set to active and the other to standby
    String activeHost = hadoopEnvProperties.get("dfs_ha_initial_namenode_active");
    if (activeHost.equals(expectedHostName)) {
      assertEquals("Standby Namenode hostname was not set correctly",
          expectedHostNameTwo, hadoopEnvProperties.get("dfs_ha_initial_namenode_standby"));
    } else if (activeHost.equals(expectedHostNameTwo)) {
      assertEquals("Standby Namenode hostname was not set correctly",
          expectedHostName, hadoopEnvProperties.get("dfs_ha_initial_namenode_standby"));
    } else {
      fail("Active Namenode hostname was not set correctly: " + activeHost);
    }

    assertEquals("fs.defaultFS should not be modified by cluster update when NameNode HA is enabled.",
        "hdfs://" + expectedNameService, coreSiteProperties.get("fs.defaultFS"));

    assertEquals("hbase.rootdir should not be modified by cluster update when NameNode HA is enabled.",
        "hdfs://" + expectedNameService + "/hbase/test/root/dir", hbaseSiteProperties.get("hbase.rootdir"));

    assertEquals("instance.volumes should not be modified by cluster update when NameNode HA is enabled.",
        "hdfs://" + expectedNameService + "/accumulo/test/instance/volumes", accumuloSiteProperties.get("instance.volumes"));
  }

  @Test
  public void testHiveConfigClusterUpdateCustomValueSpecifyingHostNamesMetaStoreHA() throws Exception {
    final String expectedHostGroupName = "host_group_1";

    final String expectedPropertyValue =
        "hive.metastore.local=false,hive.metastore.uris=thrift://headnode0.ivantestcluster2-ssh.d1.internal.cloudapp.net:9083,hive.user.install.directory=/user";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    configProperties.put("webhcat-site", webHCatSiteProperties);

    // setup properties that include host information
    webHCatSiteProperties.put("templeton.hive.properties", expectedPropertyValue);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton("some-host"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    TestHostGroup group2 = new TestHostGroup("host_group_2", hgComponents2, Collections.singleton("some-host2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    assertEquals("Unexpected config update for templeton.hive.properties",
        expectedPropertyValue,
        webHCatSiteProperties.get("templeton.hive.properties"));
  }

  @Test
  public void testHiveConfigClusterUpdateSpecifyingHostNamesHiveServer2HA() throws Exception {
    final String expectedHostGroupName = "host_group_1";

    final String expectedPropertyValue =
        "c6401.ambari.apache.org";

    final String expectedMetaStoreURIs = "thrift://c6401.ambari.apache.org:9083,thrift://c6402.ambari.apache.org:9083";

    Map<String, Map<String, String>> configProperties =
        new HashMap<String, Map<String, String>>();

    Map<String, String> hiveEnvProperties =
        new HashMap<String, String>();
    Map<String, String> hiveSiteProperties =
        new HashMap<String, String>();

    configProperties.put("hive-env", hiveEnvProperties);
    configProperties.put("hive-site", hiveSiteProperties);

    // setup properties that include host information
    hiveEnvProperties.put("hive_hostname",
        expectedPropertyValue);

    // simulate HA mode, since this property must be present in HiveServer2 HA
    hiveSiteProperties.put("hive.server2.support.dynamic.service.discovery", "true");

    // set MetaStore URIs property to reflect an HA environment for HIVE_METASTORE

    hiveSiteProperties.put("hive.metastore.uris", expectedMetaStoreURIs);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_SERVER");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton("some-host"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("HIVE_SERVER");
    TestHostGroup group2 = new TestHostGroup("host_group_2", hgComponents2, Collections.singleton("some-host2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("HIVE_SERVER")).andReturn(new Cardinality("1+")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    assertEquals("Unexpected config update for hive_hostname",
        expectedPropertyValue,
        hiveEnvProperties.get("hive_hostname"));

    assertEquals("Unexpected config update for hive.metastore.uris",
        expectedMetaStoreURIs,
        hiveSiteProperties.get("hive.metastore.uris"));
  }

  @Test
  public void testHiveConfigClusterUpdateUsingExportedNamesHiveServer2HA() throws Exception {
    final String expectedHostGroupNameOne = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    final String expectedHostNameOne =
        "c6401.ambari.apache.org";

    final String expectedHostNameTwo =
        "c6402.ambari.apache.org";


    // use exported HOSTGROUP syntax for this property, to make sure the
    // config processor updates this as expected
    final String inputMetaStoreURIs = "thrift://" + createExportedAddress("9083", expectedHostGroupNameOne) + "," + "thrift://" + createExportedAddress("9083", expectedHostGroupNameTwo);

    final String expectedMetaStoreURIs = "thrift://c6401.ambari.apache.org:9083,thrift://c6402.ambari.apache.org:9083";


    Map<String, Map<String, String>> configProperties =
        new HashMap<String, Map<String, String>>();

    Map<String, String> hiveEnvProperties =
        new HashMap<String, String>();
    Map<String, String> hiveSiteProperties =
        new HashMap<String, String>();

    configProperties.put("hive-env", hiveEnvProperties);
    configProperties.put("hive-site", hiveSiteProperties);

    // setup properties that include host information
    hiveEnvProperties.put("hive_hostname",
        expectedHostNameOne);

    // simulate HA mode, since this property must be present in HiveServer2 HA
    hiveSiteProperties.put("hive.server2.support.dynamic.service.discovery", "true");

    // set MetaStore URIs property to reflect an HA environment for HIVE_METASTORE

    hiveSiteProperties.put("hive.metastore.uris", inputMetaStoreURIs);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_SERVER");
    hgComponents.add("HIVE_METASTORE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupNameOne, hgComponents, Collections.singleton(expectedHostNameOne));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("HIVE_SERVER");
    hgComponents2.add("HIVE_METASTORE");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("HIVE_SERVER")).andReturn(new Cardinality("1+")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    assertEquals("Unexpected config update for hive_hostname",
        expectedHostNameOne,
        hiveEnvProperties.get("hive_hostname"));

    assertEquals("Unexpected config update for hive.metastore.uris",
        expectedMetaStoreURIs,
        hiveSiteProperties.get("hive.metastore.uris"));
  }

  @Test
  public void testHiveConfigClusterUpdateDefaultValueWithMetaStoreHA() throws Exception {
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostNameOne = "c6401.ambari.apache.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";

    final String expectedPropertyValue =
        "hive.metastore.local=false,hive.metastore.uris=thrift://localhost:9933,hive.metastore.sasl.enabled=false";

    Map<String, Map<String, String>> configProperties =
        new HashMap<String, Map<String, String>>();

    Map<String, String> webHCatSiteProperties =
        new HashMap<String, String>();

    configProperties.put("webhcat-site", webHCatSiteProperties);

    // setup properties that include host information
    webHCatSiteProperties.put("templeton.hive.properties",
        expectedPropertyValue);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_METASTORE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton(expectedHostNameOne));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("HIVE_METASTORE");
    TestHostGroup group2 = new TestHostGroup("host_group_2", hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    // verify that the host name for the metastore.uris property has been updated, and
    // that both MetaStore Server URIs are included, using the required Hive Syntax
    assertEquals("Unexpected config update for templeton.hive.properties",
        "hive.metastore.local=false,hive.metastore.uris=thrift://" + expectedHostNameOne + ":9933\\," + "thrift://" +
        expectedHostNameTwo + ":9933" + "," + "hive.metastore.sasl.enabled=false",
        webHCatSiteProperties.get("templeton.hive.properties"));
  }

  @Test
  public void testOozieConfigClusterUpdateHAEnabledSpecifyingHostNamesDirectly() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.ambari.apache.org";
    final String expectedExternalHost = "c6408.ambari.apache.org";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> oozieSiteProperties = new HashMap<String, String>();
    Map<String, String> oozieEnvProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();

    configProperties.put("oozie-site", oozieSiteProperties);
    configProperties.put("oozie-env", oozieEnvProperties);
    configProperties.put("hive-env", oozieEnvProperties);
    configProperties.put("core-site", coreSiteProperties);

    oozieSiteProperties.put("oozie.base.url", expectedHostName);
    oozieSiteProperties.put("oozie.authentication.kerberos.principal", expectedHostName);
    oozieSiteProperties.put("oozie.service.HadoopAccessorService.kerberos.principal", expectedHostName);
    oozieSiteProperties.put("oozie.service.JPAService.jdbc.url", "jdbc:mysql://" + expectedExternalHost + "/ooziedb");

    // simulate the Oozie HA configuration
    oozieSiteProperties.put("oozie.services.ext",
        "org.apache.oozie.service.ZKLocksService,org.apache.oozie.service.ZKXLogStreamingService,org.apache.oozie.service.ZKJobsConcurrencyService,org.apache.oozie.service.ZKUUIDService");


    oozieEnvProperties.put("oozie_hostname", expectedHostName);
    oozieEnvProperties.put("oozie_existing_mysql_host", expectedExternalHost);

    coreSiteProperties.put("hadoop.proxyuser.oozie.hosts", expectedHostName + "," + expectedHostNameTwo);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());
    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("OOZIE_SERVER");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton("host1"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("OOZIE_SERVER");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton("host2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("OOZIE_SERVER")).andReturn(new Cardinality("1+")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    assertEquals("oozie property not updated correctly",
        expectedHostName, oozieSiteProperties.get("oozie.base.url"));
    assertEquals("oozie property not updated correctly",
        expectedHostName, oozieSiteProperties.get("oozie.authentication.kerberos.principal"));
    assertEquals("oozie property not updated correctly",
        expectedHostName, oozieSiteProperties.get("oozie.service.HadoopAccessorService.kerberos.principal"));
    assertEquals("oozie property not updated correctly",
        expectedHostName, oozieEnvProperties.get("oozie_hostname"));
    assertEquals("oozie property not updated correctly",
        expectedHostName + "," + expectedHostNameTwo, coreSiteProperties.get("hadoop.proxyuser.oozie.hosts"));
  }

  @Test
  public void testYarnHighAvailabilityConfigClusterUpdateSpecifyingHostNamesDirectly() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> yarnSiteProperties = new HashMap<String, String>();
    configProperties.put("yarn-site", yarnSiteProperties);

    // setup properties that include host information
    yarnSiteProperties.put("yarn.log.server.url", "http://" + expectedHostName +":19888/jobhistory/logs");
    yarnSiteProperties.put("yarn.resourcemanager.hostname", expectedHostName);
    yarnSiteProperties.put("yarn.resourcemanager.resource-tracker.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.webapp.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.scheduler.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.admin.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.timeline-service.webapp.https.address", expectedHostName + ":" + expectedPortNum);
    yarnSiteProperties.put("yarn.resourcemanager.ha.enabled", "true");

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());
    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("RESOURCEMANAGER");
    hgComponents.add("APP_TIMELINE_SERVER");
    hgComponents.add("HISTORYSERVER");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton(expectedHostName));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("RESOURCEMANAGER");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton("host2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("RESOURCEMANAGER")).andReturn(new Cardinality("1-2")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    // verify that the properties with hostname information was correctly preserved
    assertEquals("Yarn Log Server URL was incorrectly updated",
        "http://" + expectedHostName + ":19888/jobhistory/logs", yarnSiteProperties.get("yarn.log.server.url"));
    assertEquals("Yarn ResourceManager hostname was incorrectly exported",
        expectedHostName, yarnSiteProperties.get("yarn.resourcemanager.hostname"));
    assertEquals("Yarn ResourceManager tracker address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.resourcemanager.resource-tracker.address"));
    assertEquals("Yarn ResourceManager webapp address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.resourcemanager.webapp.address"));
    assertEquals("Yarn ResourceManager scheduler address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.resourcemanager.scheduler.address"));
    assertEquals("Yarn ResourceManager address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.resourcemanager.address"));
    assertEquals("Yarn ResourceManager admin address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.resourcemanager.admin.address"));
    assertEquals("Yarn ResourceManager timeline-service address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.timeline-service.address"));
    assertEquals("Yarn ResourceManager timeline webapp address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.timeline-service.webapp.address"));
    assertEquals("Yarn ResourceManager timeline webapp HTTPS address was incorrectly updated",
        createHostAddress(expectedHostName, expectedPortNum), yarnSiteProperties.get("yarn.timeline-service.webapp.https.address"));
  }

  @Test
  public void testHDFSConfigClusterUpdateQuorumJournalURLSpecifyingHostNamesDirectly() throws Exception {
    final String expectedHostNameOne = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";
    final String expectedQuorumJournalURL = "qjournal://" + createHostAddress(expectedHostNameOne, expectedPortNum) + ";" +
        createHostAddress(expectedHostNameTwo, expectedPortNum) + "/mycluster";

    Map<String, Map<String, String>> configProperties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    configProperties.put("hdfs-site", hdfsSiteProperties);

    // setup properties that include host information
    // setup shared edit property, that includes a qjournal URL scheme

    hdfsSiteProperties.put("dfs.namenode.shared.edits.dir", expectedQuorumJournalURL);

    Configuration clusterConfig = new Configuration(configProperties, Collections.<String, Map<String, Map<String, String>>>emptyMap());
    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton("host1"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton("host2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);
    updater.doUpdateForClusterCreate();

    // expect that all servers are included in configuration property without changes, and that the qjournal URL format is preserved
    assertEquals("HDFS HA shared edits directory property should not have been modified, since FQDNs were specified.",
        expectedQuorumJournalURL,
        hdfsSiteProperties.get("dfs.namenode.shared.edits.dir"));
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__defaultValues___YAML() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("storm.zookeeper.servers", "['localhost']");
    properties.put("storm-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);


    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("storm-site").get("storm.zookeeper.servers");
    assertTrue(updatedVal.startsWith("["));
    assertTrue(updatedVal.endsWith("]"));
    // remove the surrounding brackets
    updatedVal = updatedVal.replaceAll("[\\[\\]]", "");

    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("'testhost'");
    expectedHosts.add("'testhost2'");
    expectedHosts.add("'testhost2a'");
    expectedHosts.add("'testhost2b'");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_MProperty__defaultValues() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("hbase_master_heapsize", "512m");
    properties.put("hbase-env", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hbase-env").get("hbase_master_heapsize");
    assertEquals("512m", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_MProperty__missingM() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("hbase_master_heapsize", "512");
    properties.put("hbase-env", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hbase-env").get("hbase_master_heapsize");
    assertEquals("512m", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__exportedValue() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "%HOSTGROUP::group1%");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("yarn-site").get("yarn.resourcemanager.hostname");
    assertEquals("testhost", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__exportedValue_UsingMinusSymbolInHostGroupName() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "%HOSTGROUP::os-amb-r6-secha-1427972156-hbaseha-3-6%");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("os-amb-r6-secha-1427972156-hbaseha-3-6", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("yarn-site").get("yarn.resourcemanager.hostname");
    assertEquals("testhost", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__exportedValue_WithPort_UsingMinusSymbolInHostGroupName() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("yarn.resourcemanager.hostname", "%HOSTGROUP::os-amb-r6-secha-1427972156-hbaseha-3-6%:2180");
    properties.put("yarn-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("os-amb-r6-secha-1427972156-hbaseha-3-6", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("yarn-site").get("yarn.resourcemanager.hostname");
    assertEquals("testhost:2180", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_SingleHostProperty__exportedValue__WithPort() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("fs.defaultFS", "%HOSTGROUP::group1%:5050");
    properties.put("core-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("core-site").get("fs.defaultFS");
    assertEquals("testhost:5050", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__exportedValues() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("hbase.zookeeper.quorum", "%HOSTGROUP::group1%,%HOSTGROUP::group2%");
    properties.put("hbase-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hbase-site").get("hbase.zookeeper.quorum");
    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("testhost");
    expectedHosts.add("testhost2");
    expectedHosts.add("testhost2a");
    expectedHosts.add("testhost2b");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__exportedValues___withPorts() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("templeton.zookeeper.hosts", "%HOSTGROUP::group1%:9090,%HOSTGROUP::group2%:9091");
    properties.put("webhcat-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("webhcat-site").get("templeton.zookeeper.hosts");
    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("testhost:9090");
    expectedHosts.add("testhost2:9091");
    expectedHosts.add("testhost2a:9091");
    expectedHosts.add("testhost2b:9091");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__exportedValues___withPorts_UsingMinusSymbolInHostGroupName() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("ha.zookeeper.quorum", "%HOSTGROUP::os-amb-r6-secha-1427972156-hbaseha-3-6%:2181,%HOSTGROUP::os-amb-r6-secha-1427972156-hbaseha-3-5%:2181,%HOSTGROUP::os-amb-r6-secha-1427972156-hbaseha-3-7%:2181");
    properties.put("core-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("os-amb-r6-secha-1427972156-hbaseha-3-6", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("os-amb-r6-secha-1427972156-hbaseha-3-5", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("os-amb-r6-secha-1427972156-hbaseha-3-7", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("core-site").get("ha.zookeeper.quorum");
    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("testhost:2181");
    expectedHosts.add("testhost2:2181");
    expectedHosts.add("testhost2a:2181");
    expectedHosts.add("testhost2b:2181");
    expectedHosts.add("testhost3:2181");
    expectedHosts.add("testhost3a:2181");

    assertEquals(6, hosts.length);
    for (String host : hosts) {
      assertTrue("Expected host :" + host + "was not included in the multi-server list in this property.", expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty_exportedValues_withPorts_singleHostValue() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> yarnSiteConfig = new HashMap<String, String>();

    yarnSiteConfig.put("hadoop.registry.zk.quorum", "%HOSTGROUP::host_group_1%:2181");
    properties.put("yarn-site", yarnSiteConfig);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("host_group_1", hgComponents, Collections.singleton("testhost"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    assertEquals("Multi-host property with single host value was not correctly updated for cluster create.",
      "testhost:2181", topology.getConfiguration().getFullProperties().get("yarn-site").get("hadoop.registry.zk.quorum"));
  }

  @Test
  public void testDoUpdateForClusterCreate_MultiHostProperty__exportedValues___YAML() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("storm.zookeeper.servers", "['%HOSTGROUP::group1%:9090','%HOSTGROUP::group2%:9091']");
    properties.put("storm-site", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("ZOOKEEPER_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_SERVER");
    Set<String> hosts2 = new HashSet<String>();
    hosts2.add("testhost2");
    hosts2.add("testhost2a");
    hosts2.add("testhost2b");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, hosts2);

    Collection<String> hgComponents3 = new HashSet<String>();
    hgComponents2.add("HDFS_CLIENT");
    hgComponents2.add("ZOOKEEPER_CLIENT");
    Set<String> hosts3 = new HashSet<String>();
    hosts3.add("testhost3");
    hosts3.add("testhost3a");
    TestHostGroup group3 = new TestHostGroup("group3", hgComponents3, hosts3);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);
    hostGroups.add(group3);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("storm-site").get("storm.zookeeper.servers");
    assertTrue(updatedVal.startsWith("["));
    assertTrue(updatedVal.endsWith("]"));
    // remove the surrounding brackets
    updatedVal = updatedVal.replaceAll("[\\[\\]]", "");

    String[] hosts = updatedVal.split(",");

    Collection<String> expectedHosts = new HashSet<String>();
    expectedHosts.add("'testhost:9090'");
    expectedHosts.add("'testhost2:9091'");
    expectedHosts.add("'testhost2a:9091'");
    expectedHosts.add("'testhost2b:9091'");

    assertEquals(4, hosts.length);
    for (String host : hosts) {
      assertTrue(expectedHosts.contains(host));
      expectedHosts.remove(host);
    }
  }

  @Test
  public void testDoUpdateForClusterCreate_DBHostProperty__defaultValue() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hiveSiteProps = new HashMap<String, String>();
    hiveSiteProps.put("javax.jdo.option.ConnectionURL", "jdbc:mysql://localhost/hive?createDatabaseIfNotExist=true");
    Map<String, String> hiveEnvProps = new HashMap<String, String>();
    hiveEnvProps.put("hive_database", "New MySQL Database");
    properties.put("hive-site", hiveSiteProps);
    properties.put("hive-env", hiveEnvProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    hgComponents.add("MYSQL_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hive-site").get("javax.jdo.option.ConnectionURL");
    assertEquals("jdbc:mysql://testhost/hive?createDatabaseIfNotExist=true", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_DBHostProperty__exportedValue() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hiveSiteProps = new HashMap<String, String>();
    hiveSiteProps.put("javax.jdo.option.ConnectionURL", "jdbc:mysql://%HOSTGROUP::group1%/hive?createDatabaseIfNotExist=true");
    Map<String, String> hiveEnvProps = new HashMap<String, String>();
    hiveEnvProps.put("hive_database", "New MySQL Database");
    properties.put("hive-site", hiveSiteProps);
    properties.put("hive-env", hiveEnvProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    hgComponents.add("MYSQL_SERVER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hive-site").get("javax.jdo.option.ConnectionURL");
    assertEquals("jdbc:mysql://testhost/hive?createDatabaseIfNotExist=true", updatedVal);
  }

  @Test
  public void testDoUpdateForClusterCreate_DBHostProperty__external() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> typeProps = new HashMap<String, String>();
    typeProps.put("javax.jdo.option.ConnectionURL", "jdbc:mysql://myHost.com/hive?createDatabaseIfNotExist=true");
    typeProps.put("hive_database", "Existing MySQL Database");
    properties.put("hive-env", typeProps);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    hgComponents.add("SECONDARY_NAMENODE");
    hgComponents.add("RESOURCEMANAGER");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents, Collections.singleton("testhost"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    hgComponents2.add("HDFS_CLIENT");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("testhost2"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();
    String updatedVal = topology.getConfiguration().getFullProperties().get("hive-env").get("javax.jdo.option.ConnectionURL");
    assertEquals("jdbc:mysql://myHost.com/hive?createDatabaseIfNotExist=true", updatedVal);
  }

  @Test
  public void testFalconConfigClusterUpdate() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";


    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> falconStartupProperties = new HashMap<String, String>();
    properties.put("falcon-startup.properties", falconStartupProperties);

    // setup properties that include host information
    falconStartupProperties.put("*.broker.url", createExportedAddress(expectedPortNum, expectedHostGroupName));
    falconStartupProperties.put("*.falcon.service.authentication.kerberos.principal", "falcon/" + createExportedHostName(expectedHostGroupName) + "@EXAMPLE.COM");
    falconStartupProperties.put("*.falcon.http.authentication.kerberos.principal", "HTTP/" + createExportedHostName(expectedHostGroupName) + "@EXAMPLE.COM");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("FALCON_SERVER");
    hgComponents.add("FALCON_CLIENT");
    List<String> hosts = new ArrayList<String>();
    hosts.add("c6401.apache.ambari.org");
    hosts.add("serverTwo");
    TestHostGroup group1 = new TestHostGroup("host_group_1", hgComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();

    assertEquals("Falcon Broker URL property not properly exported",
      expectedHostName + ":" + expectedPortNum, falconStartupProperties.get("*.broker.url"));

    assertEquals("Falcon Kerberos Principal property not properly exported",
        "falcon/" + expectedHostName + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.service.authentication.kerberos.principal"));

    assertEquals("Falcon Kerberos HTTP Principal property not properly exported",
      "HTTP/" + expectedHostName + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.http.authentication.kerberos.principal"));
  }

  @Test
  //todo: fails because there are multiple hosts mapped to the hostgroup
  //todo: but error message states that the component is mapped to 0 or multiple host groups
  //todo: This test fails now but passed before
  public void testFalconConfigClusterUpdateDefaultConfig() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> falconStartupProperties = new HashMap<String, String>();
    properties.put("falcon-startup.properties", falconStartupProperties);

    // setup properties that include host information
    falconStartupProperties.put("*.broker.url", "localhost:" + expectedPortNum);
    falconStartupProperties.put("*.falcon.service.authentication.kerberos.principal", "falcon/" + "localhost" + "@EXAMPLE.COM");
    falconStartupProperties.put("*.falcon.http.authentication.kerberos.principal", "HTTP/" + "localhost" + "@EXAMPLE.COM");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("FALCON_SERVER");
    hgComponents.add("FALCON_CLIENT");
    List<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add("serverTwo");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    updater.doUpdateForClusterCreate();

    assertEquals("Falcon Broker URL property not properly exported",
      expectedHostName + ":" + expectedPortNum, falconStartupProperties.get("*.broker.url"));

    assertEquals("Falcon Kerberos Principal property not properly exported",
      "falcon/" + expectedHostName + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.service.authentication.kerberos.principal"));

    assertEquals("Falcon Kerberos HTTP Principal property not properly exported",
      "HTTP/" + expectedHostName + "@EXAMPLE.COM", falconStartupProperties.get("*.falcon.http.authentication.kerberos.principal"));
  }

  @Test
  public void testHiveConfigClusterUpdateCustomValue() throws Exception {
    final String expectedHostGroupName = "host_group_1";

    final String expectedPropertyValue =
      "hive.metastore.local=false,hive.metastore.uris=thrift://headnode0.ivantestcluster2-ssh.d1.internal.cloudapp.net:9083,hive.user.install.directory=/user";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    properties.put("webhcat-site", webHCatSiteProperties);

    // setup properties that include host information
    webHCatSiteProperties.put("templeton.hive.properties", expectedPropertyValue);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    List<String> hosts = new ArrayList<String>();
    hosts.add("some-hose");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, hosts);


    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level cluster config update method
    updater.doUpdateForClusterCreate();

    assertEquals("Unexpected config update for templeton.hive.properties",
      expectedPropertyValue,
      webHCatSiteProperties.get("templeton.hive.properties"));
  }

  @Test
  public void testHiveConfigClusterUpdateDefaultValue() throws Exception {
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostName = "c6401.ambari.apache.org";

    final String expectedPropertyValue =
      "hive.metastore.local=false,hive.metastore.uris=thrift://localhost:9933,hive.metastore.sasl.enabled=false";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    properties.put("webhcat-site", webHCatSiteProperties);

    // setup properties that include host information
    webHCatSiteProperties.put("templeton.hive.properties",
      expectedPropertyValue);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_METASTORE");
    List<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level cluster config update method
    updater.doUpdateForClusterCreate();

    // verify that the host name for the metastore.uris property has been updated
    assertEquals("Unexpected config update for templeton.hive.properties",
      "hive.metastore.local=false,hive.metastore.uris=thrift://" + expectedHostName + ":9933,hive.metastore.sasl.enabled=false",
      webHCatSiteProperties.get("templeton.hive.properties"));
  }

  @Test
  public void testHiveConfigClusterUpdateExportedHostGroupValue() throws Exception {
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostName = "c6401.ambari.apache.org";

    // simulate the case of this property coming from an exported Blueprint
    final String expectedPropertyValue =
      "hive.metastore.local=false,hive.metastore.uris=thrift://%HOSTGROUP::host_group_1%:9083,hive.metastore.sasl.enabled=false,hive.metastore.execute.setugi=true";


    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> webHCatSiteProperties = new HashMap<String, String>();
    properties.put("webhcat-site", webHCatSiteProperties);

    // setup properties that include host information
    webHCatSiteProperties.put("templeton.hive.properties",
      expectedPropertyValue);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_METASTORE");
    List<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, hosts);

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level cluster config update method
    updater.doUpdateForClusterCreate();

    // verify that the host name for the metastore.uris property has been updated
    assertEquals("Unexpected config update for templeton.hive.properties",
      "hive.metastore.local=false,hive.metastore.uris=thrift://" + expectedHostName + ":9083,hive.metastore.sasl.enabled=false,hive.metastore.execute.setugi=true",
      webHCatSiteProperties.get("templeton.hive.properties"));
  }

  @Test
  public void testStormAndKafkaConfigClusterUpdateWithoutGangliaServer() throws Exception {
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> stormSiteProperties = new HashMap<String, String>();
    Map<String, String> kafkaBrokerProperties = new HashMap<String, String>();

    properties.put("storm-site", stormSiteProperties);
    properties.put("kafka-broker", kafkaBrokerProperties);

    stormSiteProperties.put("worker.childopts", "localhost");
    stormSiteProperties.put("supervisor.childopts", "localhost");
    stormSiteProperties.put("nimbus.childopts", "localhost");

    kafkaBrokerProperties.put("kafka.ganglia.metrics.host", "localhost");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("HIVE_METASTORE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton("testserver"));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    expect(stack.getCardinality("GANGLIA_SERVER")).andReturn(new Cardinality("1")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    updater.doUpdateForClusterCreate();

    // verify that the server name is not replaced, since the GANGLIA_SERVER
    // component is not available
    assertEquals("worker startup settings not properly handled by cluster create",
      "localhost", stormSiteProperties.get("worker.childopts"));

    assertEquals("supervisor startup settings not properly handled by cluster create",
      "localhost", stormSiteProperties.get("supervisor.childopts"));

    assertEquals("nimbus startup settings not properly handled by cluster create",
        "localhost", stormSiteProperties.get("nimbus.childopts"));

    assertEquals("Kafka ganglia host property not properly handled by cluster create",
      "localhost", kafkaBrokerProperties.get("kafka.ganglia.metrics.host"));
  }

  @Test
  public void testStormandKafkaConfigClusterUpdateWithGangliaServer() throws Exception {
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> stormSiteProperties = new HashMap<String, String>();
    Map<String, String> kafkaBrokerProperties = new HashMap<String, String>();

    properties.put("storm-site", stormSiteProperties);
    properties.put("kafka-broker", kafkaBrokerProperties);

    stormSiteProperties.put("worker.childopts", "localhost");
    stormSiteProperties.put("supervisor.childopts", "localhost");
    stormSiteProperties.put("nimbus.childopts", "localhost");

    kafkaBrokerProperties.put("kafka.ganglia.metrics.host", "localhost");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("GANGLIA_SERVER");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton(expectedHostName));

    Collection<TestHostGroup> hostGroups = new HashSet<TestHostGroup>();
    hostGroups.add(group1);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    updater.doUpdateForClusterCreate();

    // verify that the server name is not replaced, since the GANGLIA_SERVER
    // component is not available
    assertEquals("worker startup settings not properly handled by cluster create",
      expectedHostName, stormSiteProperties.get("worker.childopts"));

    assertEquals("supervisor startup settings not properly handled by cluster create",
      expectedHostName, stormSiteProperties.get("supervisor.childopts"));

    assertEquals("nimbus startup settings not properly handled by cluster create",
      expectedHostName, stormSiteProperties.get("nimbus.childopts"));

    assertEquals("Kafka ganglia host property not properly handled by cluster create",
      expectedHostName, kafkaBrokerProperties.get("kafka.ganglia.metrics.host"));
  }

  @Test
  public void testDoUpdateForClusterWithNameNodeHAEnabled() throws Exception {
    final String expectedNameService = "mynameservice";
    final String expectedHostName = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "serverTwo";
    final String expectedPortNum = "808080";
    final String expectedNodeOne = "nn1";
    final String expectedNodeTwo = "nn2";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();

    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    Map<String, String> hbaseSiteProperties = new HashMap<String, String>();
    Map<String, String> hadoopEnvProperties = new HashMap<String, String>();
    Map<String, String> coreSiteProperties = new HashMap<String, String>();
    Map<String, String> accumuloSiteProperties =new HashMap<String, String>();

    properties.put("hdfs-site", hdfsSiteProperties);
    properties.put("hadoop-env", hadoopEnvProperties);
    properties.put("core-site", coreSiteProperties);
    properties.put("hbase-site", hbaseSiteProperties);
    properties.put("accumulo-site", accumuloSiteProperties);

    // setup hdfs HA config for test
    hdfsSiteProperties.put("dfs.nameservices", expectedNameService);
    hdfsSiteProperties.put("dfs.ha.namenodes.mynameservice", expectedNodeOne + ", " + expectedNodeTwo);

    // setup properties that include exported host group information
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));

    // add properties that require the SECONDARY_NAMENODE, which
    // is not included in this test
    hdfsSiteProperties.put("dfs.secondary.http.address", "localhost:8080");
    hdfsSiteProperties.put("dfs.namenode.secondary.http-address", "localhost:8080");

    // configure the defaultFS to use the nameservice URL
    coreSiteProperties.put("fs.defaultFS", "hdfs://" + expectedNameService);

    // configure the hbase rootdir to use the nameservice URL
    hbaseSiteProperties.put("hbase.rootdir", "hdfs://" + expectedNameService + "/hbase/test/root/dir");

    // configure the hbase rootdir to use the nameservice URL
    accumuloSiteProperties.put("instance.volumes", "hdfs://" + expectedNameService + "/accumulo/test/instance/volumes");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents, Collections.singleton(expectedHostName));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("NAMENODE");
    TestHostGroup group2 = new TestHostGroup("host-group-2", hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new ArrayList<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    expect(stack.getCardinality("NAMENODE")).andReturn(new Cardinality("1-2")).anyTimes();
    expect(stack.getCardinality("SECONDARY_NAMENODE")).andReturn(new Cardinality("1")).anyTimes();

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();

    // verify that the expected hostname was substituted for the host group name in the config
    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported",
      expectedHostName + ":" + expectedPortNum, hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo));

    // verify that the Blueprint config processor has set the internal required properties
    // that determine the active and standby node hostnames for this HA setup.
    // one host should be active and the other standby
    String initialActiveHost = hadoopEnvProperties.get("dfs_ha_initial_namenode_active");
    String expectedStandbyHost = null;
    if (initialActiveHost.equals(expectedHostName)) {
      expectedStandbyHost = expectedHostNameTwo;
    } else if (initialActiveHost.equals(expectedHostNameTwo)) {
      expectedStandbyHost = expectedHostName;
    } else {
      fail("Active Namenode hostname was not set correctly");
    }
    assertEquals("Standby Namenode hostname was not set correctly",
      expectedStandbyHost, hadoopEnvProperties.get("dfs_ha_initial_namenode_standby"));


    assertEquals("fs.defaultFS should not be modified by cluster update when NameNode HA is enabled.",
                 "hdfs://" + expectedNameService, coreSiteProperties.get("fs.defaultFS"));

    assertEquals("hbase.rootdir should not be modified by cluster update when NameNode HA is enabled.",
      "hdfs://" + expectedNameService + "/hbase/test/root/dir", hbaseSiteProperties.get("hbase.rootdir"));

    assertEquals("instance.volumes should not be modified by cluster update when NameNode HA is enabled.",
        "hdfs://" + expectedNameService + "/accumulo/test/instance/volumes", accumuloSiteProperties.get("instance.volumes"));
  }

  @Test
  public void testDoUpdateForClusterWithNameNodeHAEnabledAndActiveNodeSet() throws Exception {
    final String expectedNameService = "mynameservice";
    final String expectedHostName = "serverThree";
    final String expectedHostNameTwo = "serverFour";
    final String expectedPortNum = "808080";
    final String expectedNodeOne = "nn1";
    final String expectedNodeTwo = "nn2";
    final String expectedHostGroupName = "host_group_1";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    Map<String, String> hadoopEnvProperties = new HashMap<String, String>();

    properties.put("hdfs-site", hdfsSiteProperties);
    properties.put("hadoop-env", hadoopEnvProperties);

    // setup hdfs HA config for test
    hdfsSiteProperties.put("dfs.nameservices", expectedNameService);
    hdfsSiteProperties.put("dfs.ha.namenodes.mynameservice", expectedNodeOne + ", " + expectedNodeTwo);

    // setup properties that include exported host group information
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne, createExportedAddress(expectedPortNum, expectedHostGroupName));
    hdfsSiteProperties.put("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo, createExportedAddress(expectedPortNum, expectedHostGroupName));

    // set hadoop-env properties to explicitly configure the initial
    // active and stanbdy namenodes
    hadoopEnvProperties.put("dfs_ha_initial_namenode_active", expectedHostName);
    hadoopEnvProperties.put("dfs_ha_initial_namenode_standby", expectedHostNameTwo);

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents = new HashSet<String>();
    hgComponents.add("NAMENODE");
    Collection<String> hosts = new ArrayList<String>();
    hosts.add(expectedHostName);
    hosts.add(expectedHostNameTwo);
    TestHostGroup group = new TestHostGroup(expectedHostGroupName, hgComponents, hosts);

    Collection<TestHostGroup> hostGroups = new ArrayList<TestHostGroup>();
    hostGroups.add(group);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    updater.doUpdateForClusterCreate();

    // verify that the expected hostname was substituted for the host group name in the config.
    // all of these dynamic props will be set to the same host in this case where there is a single host group
    // with multiple hosts.  This may not be correct and a Jira is being filed to track this issue.
    String expectedPropertyValue = hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeOne);
    if (! expectedPropertyValue.equals(expectedHostName + ":" + expectedPortNum) &&
        ! expectedPropertyValue.equals(expectedHostNameTwo + ":" + expectedPortNum)) {
      fail("HTTPS address HA property not properly exported");
    }
    assertEquals("HTTPS address HA property not properly exported", expectedPropertyValue,
        hdfsSiteProperties.get("dfs.namenode.https-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported", expectedPropertyValue,
        hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported", expectedPropertyValue,
        hdfsSiteProperties.get("dfs.namenode.http-address." + expectedNameService + "." + expectedNodeTwo));

    assertEquals("HTTPS address HA property not properly exported", expectedPropertyValue,
        hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeOne));
    assertEquals("HTTPS address HA property not properly exported", expectedPropertyValue,
        hdfsSiteProperties.get("dfs.namenode.rpc-address." + expectedNameService + "." + expectedNodeTwo));

    // verify that the Blueprint config processor has not overridden
    // the user's configuration to determine the active and
    // standby nodes in this NameNode HA cluster
    assertEquals("Active Namenode hostname was not set correctly",
      expectedHostName, hadoopEnvProperties.get("dfs_ha_initial_namenode_active"));

    assertEquals("Standby Namenode hostname was not set correctly",
      expectedHostNameTwo, hadoopEnvProperties.get("dfs_ha_initial_namenode_standby"));
  }

  @Test
  public void testParseNameServices() throws Exception {
    Map<String, String> hdfsSiteConfigMap =
      new HashMap<String, String>();
    hdfsSiteConfigMap.put("dfs.nameservices", "serviceOne");

    // verify that a single service is parsed correctly
    String[] result = BlueprintConfigurationProcessor.parseNameServices(hdfsSiteConfigMap);

    assertNotNull("Resulting array was null",
      result);
    assertEquals("Incorrect array size",
      1, result.length);
    assertEquals("Incorrect value for returned name service",
      "serviceOne", result[0]);

    // verify that multiple services are parsed correctly
    hdfsSiteConfigMap.put("dfs.nameservices", " serviceTwo, serviceThree, serviceFour");

    String[] resultTwo = BlueprintConfigurationProcessor.parseNameServices(hdfsSiteConfigMap);

    assertNotNull("Resulting array was null",
      resultTwo);
    assertEquals("Incorrect array size",
      3, resultTwo.length);
    assertEquals("Incorrect value for returned name service",
      "serviceTwo", resultTwo[0]);
    assertEquals("Incorrect value for returned name service",
      "serviceThree", resultTwo[1]);
    assertEquals("Incorrect value for returned name service",
      "serviceFour", resultTwo[2]);
  }

  @Test
  public void testParseNameNodes() throws Exception {
    final String expectedServiceName = "serviceOne";
    Map<String, String> hdfsSiteConfigMap =
      new HashMap<String, String>();
    hdfsSiteConfigMap.put("dfs.ha.namenodes." + expectedServiceName, "node1");

    // verify that a single name node is parsed correctly
    String[] result =
      BlueprintConfigurationProcessor.parseNameNodes(expectedServiceName, hdfsSiteConfigMap);

    assertNotNull("Resulting array was null",
      result);
    assertEquals("Incorrect array size",
      1, result.length);
    assertEquals("Incorrect value for returned name nodes",
      "node1", result[0]);

    // verify that multiple name nodes are parsed correctly
    hdfsSiteConfigMap.put("dfs.ha.namenodes." + expectedServiceName, " nodeSeven, nodeEight, nodeNine");

    String[] resultTwo =
      BlueprintConfigurationProcessor.parseNameNodes(expectedServiceName, hdfsSiteConfigMap);

    assertNotNull("Resulting array was null",
      resultTwo);
    assertEquals("Incorrect array size",
      3, resultTwo.length);
    assertEquals("Incorrect value for returned name node",
      "nodeSeven", resultTwo[0]);
    assertEquals("Incorrect value for returned name node",
      "nodeEight", resultTwo[1]);
    assertEquals("Incorrect value for returned name node",
      "nodeNine", resultTwo[2]);
  }

  @Test
  public void testHDFSConfigClusterUpdateQuorumJournalURL() throws Exception {
    final String expectedHostNameOne = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host_group_1";
    final String expectedHostGroupNameTwo = "host_group_2";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    properties.put("hdfs-site", hdfsSiteProperties);

    // setup properties that include host information
    // setup shared edit property, that includes a qjournal URL scheme
    hdfsSiteProperties.put("dfs.namenode.shared.edits.dir", "qjournal://" + createExportedAddress(expectedPortNum, expectedHostGroupName) + ";" + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo) + "/mycluster");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents1 = new HashSet<String>();
    hgComponents1.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents1, Collections.singleton(expectedHostNameOne));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("NAMENODE");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new ArrayList<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    updater.doUpdateForClusterCreate();

    // expect that all servers are included in the updated config, and that the qjournal URL format is preserved
    assertEquals("HDFS HA shared edits directory property not properly updated for cluster create.",
      "qjournal://" + createHostAddress(expectedHostNameOne, expectedPortNum) + ";" + createHostAddress(expectedHostNameTwo, expectedPortNum) + "/mycluster",
      hdfsSiteProperties.get("dfs.namenode.shared.edits.dir"));
  }

  @Test
  public void testHDFSConfigClusterUpdateQuorumJournalURL_UsingMinusSymbolInHostName() throws Exception {
    final String expectedHostNameOne = "c6401.apache.ambari.org";
    final String expectedHostNameTwo = "c6402.apache.ambari.org";
    final String expectedPortNum = "808080";
    final String expectedHostGroupName = "host-group-1";
    final String expectedHostGroupNameTwo = "host-group-2";

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hdfsSiteProperties = new HashMap<String, String>();
    properties.put("hdfs-site", hdfsSiteProperties);

    // setup properties that include host information
    // setup shared edit property, that includes a qjournal URL scheme
    hdfsSiteProperties.put("dfs.namenode.shared.edits.dir", "qjournal://" + createExportedAddress(expectedPortNum, expectedHostGroupName) + ";" + createExportedAddress(expectedPortNum, expectedHostGroupNameTwo) + "/mycluster");

    Configuration clusterConfig = new Configuration(properties, Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents1 = new HashSet<String>();
    hgComponents1.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup(expectedHostGroupName, hgComponents1, Collections.singleton(expectedHostNameOne));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("NAMENODE");
    TestHostGroup group2 = new TestHostGroup(expectedHostGroupNameTwo, hgComponents2, Collections.singleton(expectedHostNameTwo));

    Collection<TestHostGroup> hostGroups = new ArrayList<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    updater.doUpdateForClusterCreate();

    // expect that all servers are included in the updated config, and that the qjournal URL format is preserved
    assertEquals("HDFS HA shared edits directory property not properly updated for cluster create.",
      "qjournal://" + createHostAddress(expectedHostNameOne, expectedPortNum) + ";" + createHostAddress(expectedHostNameTwo, expectedPortNum) + "/mycluster",
      hdfsSiteProperties.get("dfs.namenode.shared.edits.dir"));
  }

  @Test
  public void testGetRequiredHostGroups___validComponentCountofZero() throws Exception {
    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
    Map<String, String> hiveSite = new HashMap<String, String>();
    properties.put("hive-site", hiveSite);
    Map<String, String> hiveEnv = new HashMap<String, String>();
    properties.put("hive-env", hiveEnv);

    hiveSite.put("javax.jdo.option.ConnectionURL", "localhost:1111");
    // not the exact string but we are only looking for "New"
    hiveEnv.put("hive_database", "New Database");


    Configuration clusterConfig = new Configuration(properties,
        Collections.<String, Map<String, Map<String, String>>>emptyMap());

    Collection<String> hgComponents1 = new HashSet<String>();
    hgComponents1.add("HIVE_SERVER");
    hgComponents1.add("NAMENODE");
    TestHostGroup group1 = new TestHostGroup("group1", hgComponents1, Collections.singleton("host1"));

    Collection<String> hgComponents2 = new HashSet<String>();
    hgComponents2.add("DATANODE");
    TestHostGroup group2 = new TestHostGroup("group2", hgComponents2, Collections.singleton("host2"));

    Collection<TestHostGroup> hostGroups = new ArrayList<TestHostGroup>();
    hostGroups.add(group1);
    hostGroups.add(group2);

    ClusterTopology topology = createClusterTopology("c1", bp, clusterConfig, hostGroups);
    BlueprintConfigurationProcessor updater = new BlueprintConfigurationProcessor(topology);

    // call top-level export method
    Collection<String> requiredGroups = updater.getRequiredHostGroups();
    System.out.println("Required Groups: " + requiredGroups);


  }

  private static String createExportedAddress(String expectedPortNum, String expectedHostGroupName) {
    return createExportedHostName(expectedHostGroupName, expectedPortNum);
  }

  private static String createExportedHostName(String expectedHostGroupName, String expectedPortNumber) {
    return createExportedHostName(expectedHostGroupName) + ":" + expectedPortNumber;
  }


  private static String createExportedHostName(String expectedHostGroupName) {
    return "%HOSTGROUP::" + expectedHostGroupName + "%";
  }

  private static String createHostAddress(String hostName, String portNumber) {
    return hostName + ":" + portNumber;
  }

  private ClusterTopology createClusterTopology(String clusterName, Blueprint blueprint, Configuration configuration,
                                                Collection<TestHostGroup> hostGroups)
      throws InvalidTopologyException {


    replay(stack, serviceInfo);

    Map<String, HostGroupInfo> hostGroupInfo = new HashMap<String, HostGroupInfo>();
    Collection<String> allServices = new HashSet<String>();
    Map<String, HostGroup> allHostGroups = new HashMap<String, HostGroup>();

    for (TestHostGroup hostGroup : hostGroups) {
      HostGroupInfo groupInfo = new HostGroupInfo(hostGroup.name);
      groupInfo.addHosts(hostGroup.hosts);
      //todo: HG configs
      groupInfo.setConfiguration(EMPTY_CONFIG);

      //create host group which is set on topology
      allHostGroups.put(hostGroup.name, new HostGroupImpl(hostGroup.name, "test-bp", stack,
          hostGroup.components, EMPTY_CONFIG, "1"));

      hostGroupInfo.put(hostGroup.name, groupInfo);

      for (String component : hostGroup.components) {
        for (Map.Entry<String, Collection<String>> serviceComponentsEntry : serviceComponents.entrySet()) {
          if (serviceComponentsEntry.getValue().contains(component)) {
            allServices.add(serviceComponentsEntry.getKey());
          }
        }
      }
    }

    expect(bp.getServices()).andReturn(allServices).anyTimes();

    for (HostGroup group : allHostGroups.values()) {
      expect(bp.getHostGroup(group.getName())).andReturn(group).anyTimes();
    }

    expect(bp.getHostGroups()).andReturn(allHostGroups).anyTimes();

    replay(bp);

    return new ClusterTopologyImpl(clusterName, blueprint, configuration, hostGroupInfo);
  }

  private class TestHostGroup {
    private String name;
    private Collection<String> components;
    private Collection<String> hosts;

    public TestHostGroup(String name, Collection<String> components, Collection<String> hosts) {
      this.name = name;
      this.components = components;
      this.hosts = hosts;
    }
  }

}
