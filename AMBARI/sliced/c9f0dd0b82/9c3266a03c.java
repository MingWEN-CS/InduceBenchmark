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


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates configuration properties based on cluster topology.  This is done when exporting
 * a blueprint and when a cluster is provisioned via a blueprint.
 */
public class BlueprintConfigurationProcessor {

  /**
   * Single host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> singleHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Multi host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> multiHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Database host topology updaters
   */
  private static Map<String, Map<String, PropertyUpdater>> dbHostTopologyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Updaters for properties which need 'm' appended
   */
  private static Map<String, Map<String, PropertyUpdater>> mPropertyUpdaters =
      new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Updaters that preserve the original property value, functions
   *   as a placeholder for DB-related properties that need to be
   *   removed from export, but do not require an update during
   *   cluster creation
   */
  private static Map<String, Map<String, PropertyUpdater>> removePropertyUpdaters =
    new HashMap<String, Map<String, PropertyUpdater>>();

  /**
   * Collection of all updaters
   */
  private static Collection<Map<String, Map<String, PropertyUpdater>>> allUpdaters =
      new ArrayList<Map<String, Map<String, PropertyUpdater>>>();

  /**
   * Compiled regex for hostgroup token.
   */
  private static Pattern HOSTGROUP_REGEX = Pattern.compile("%HOSTGROUP::(\\S+?)%");

  /**
   * Compiled regex for hostgroup token with port information.
   */
  private static Pattern HOSTGROUP_PORT_REGEX = Pattern.compile("%HOSTGROUP::(\\S+?)%:?(\\d+)?");

  /**
   * Statically-defined set of properties that can support using a nameservice name
   *   in the configuration, rather than just a host name.
   */
  private static Set<String> configPropertiesWithHASupport =
    new HashSet<String>(Arrays.asList("fs.defaultFS", "hbase.rootdir", "instance.volumes"));



  /**
   * Configuration properties to be updated
   */
  private Map<String, Map<String, String>> properties;


  /**
   * Constructor.
   *
   * @param properties  properties to update
   */
  public BlueprintConfigurationProcessor(Map<String, Map<String, String>> properties) {
    this.properties = properties;
  }

  /**
   * Update properties for cluster creation.  This involves updating topology related properties with
   * concrete topology information.
   *
   * @param hostGroups       host groups of cluster to be deployed
   * @param stackDefinition  stack used for cluster creation
   *
   * @return  updated properties
   */
  public Map<String, Map<String, String>> doUpdateForClusterCreate(Map<String, ? extends HostGroup> hostGroups, Stack stackDefinition) {
    for (Map<String, Map<String, PropertyUpdater>> updaterMap : createCollectionOfUpdaters()) {
      for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaterMap.entrySet()) {
        String type = entry.getKey();
        for (Map.Entry<String, PropertyUpdater> updaterEntry : entry.getValue().entrySet()) {
          String propertyName = updaterEntry.getKey();
          PropertyUpdater updater = updaterEntry.getValue();

          Map<String, String> typeMap = properties.get(type);
          if (typeMap != null && typeMap.containsKey(propertyName)) {
            typeMap.put(propertyName, updater.updateForClusterCreate(
                hostGroups, propertyName, typeMap.get(propertyName), properties, stackDefinition));
          }
        }
      }
    }

    if (isNameNodeHAEnabled()) {
      // if the active/stanbdy namenodes are not specified, assign them automatically
      if (! isNameNodeHAInitialActiveNodeSet(properties) && ! isNameNodeHAInitialStandbyNodeSet(properties)) {
        Collection<HostGroup> listOfHostGroups = new LinkedList<HostGroup>();
        for (String key : hostGroups.keySet()) {
          listOfHostGroups.add(hostGroups.get(key));
        }

        Collection<HostGroup> hostGroupsContainingNameNode =
          getHostGroupsForComponent("NAMENODE", listOfHostGroups);
        // set the properties that configure which namenode is active,
        // and which is a standby node in this HA deployment
        Map<String, String> hadoopEnv = properties.get("hadoop-env");
        if (hostGroupsContainingNameNode.size() == 2) {
          List<HostGroup> listOfGroups = new LinkedList<HostGroup>(hostGroupsContainingNameNode);
          hadoopEnv.put("dfs_ha_initial_namenode_active", listOfGroups.get(0).getHostInfo().iterator().next());
          hadoopEnv.put("dfs_ha_initial_namenode_standby", listOfGroups.get(1).getHostInfo().iterator().next());
        } else {
          // handle the case where multiple hosts are mapped to an HA host group
          if (hostGroupsContainingNameNode.size() == 1) {
            List<String> listOfInfo = new LinkedList<String>(hostGroupsContainingNameNode.iterator().next().getHostInfo());
            // there should only be two host names that can include a NameNode install/deployment
            hadoopEnv.put("dfs_ha_initial_namenode_active", listOfInfo.get(0));
            hadoopEnv.put("dfs_ha_initial_namenode_standby", listOfInfo.get(1));
          }
        }
      }
    }

    return properties;
  }

  /**
   * Creates a Collection of PropertyUpdater maps that will handle the configuration
   *   update for this cluster.  If NameNode HA is enabled, then updater
   *   instances will be added to the collection, in addition to the default list
   *   of Updaters that are statically defined.
   *
   * @return Collection of PropertyUpdater maps used to handle cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> createCollectionOfUpdaters() {
    return (isNameNodeHAEnabled()) ? addHAUpdaters(allUpdaters) : allUpdaters;
  }

  /**
   * Creates a Collection of PropertyUpdater maps that include the NameNode HA properties, and
   *   adds these to the list of updaters used to process the cluster configuration.  The HA
   *   properties are based on the names of the HA namservices and name nodes, and so must
   *   be registered at runtime, rather than in the static list.  This new Collection includes
   *   the statically-defined updaters, in addition to the HA-related updaters.
   *
   * @param updaters a Collection of updater maps to be included in the list of updaters for
   *                   this cluster config update
   * @return A Collection of PropertyUpdater maps to handle the cluster config update
   */
  private Collection<Map<String, Map<String, PropertyUpdater>>> addHAUpdaters(Collection<Map<String, Map<String, PropertyUpdater>>> updaters) {
    Collection<Map<String, Map<String, PropertyUpdater>>> highAvailabilityUpdaters =
      new LinkedList<Map<String, Map<String, PropertyUpdater>>>();

    // always add the statically-defined list of updaters to the list to use
    // in processing cluster configuration
    highAvailabilityUpdaters.addAll(updaters);

    // add the updaters for the dynamic HA properties, based on the HA config in hdfs-site
    highAvailabilityUpdaters.add(createMapOfHAUpdaters());

    return highAvailabilityUpdaters;
  }

  /**
   * Update properties for blueprint export.
   * This involves converting concrete topology information to host groups.
   *
   * @param hostGroups  cluster host groups
   *
   * @return  updated properties
   */
  public Map<String, Map<String, String>> doUpdateForBlueprintExport(Collection<? extends HostGroup> hostGroups) {
    doSingleHostExportUpdate(hostGroups, singleHostTopologyUpdaters);
    doSingleHostExportUpdate(hostGroups, dbHostTopologyUpdaters);

    if (isNameNodeHAEnabled()) {
      doNameNodeHAUpdate(hostGroups);
    }

    doMultiHostExportUpdate(hostGroups, multiHostTopologyUpdaters);

    doRemovePropertyExport(removePropertyUpdaters);

    return properties;
  }

  /**
   * Performs export update for the set of properties that do not
   * require update during cluster setup, but should be removed
   * during a Blueprint export.
   *
   * In the case of a service referring to an external DB, any
   * properties that contain external host information should
   * be removed from the configuration that will be available in
   * the exported Blueprint.
   *
   * @param updaters set of updaters for properties that should
   *                 always be removed during a Blueprint export
   */
  private void doRemovePropertyExport(Map<String, Map<String, PropertyUpdater>> updaters) {
    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        Map<String, String> typeProperties = properties.get(type);
        if ( (typeProperties != null) && (typeProperties.containsKey(propertyName)) ) {
          typeProperties.remove(propertyName);
        }
      }
    }
  }

  /**
   * Perform export update processing for HA configuration for NameNodes.  The HA NameNode property
   *   names are based on the nameservices defined when HA is enabled via the Ambari UI, so this method
   *   dynamically determines the property names, and registers PropertyUpdaters to handle the masking of
   *   host names in these configuration items.
   *
   * @param hostGroups cluster host groups
   */
  public void doNameNodeHAUpdate(Collection<? extends HostGroup> hostGroups) {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = createMapOfHAUpdaters();

    // perform a single host update on these dynamically generated property names
    if (highAvailabilityUpdaters.get("hdfs-site").size() > 0) {
      doSingleHostExportUpdate(hostGroups, highAvailabilityUpdaters);
    }
  }

  /**
   * Creates map of PropertyUpdater instances that are associated with
   *   NameNode High Availability (HA).  The HA configuration property
   *   names are dynamic, and based on other HA config elements in
   *   hdfs-site.  This method registers updaters for the required
   *   properties associated with each nameservice and namenode.
   *
   * @return a Map of registered PropertyUpdaters for handling HA properties in hdfs-site
   */
  private Map<String, Map<String, PropertyUpdater>> createMapOfHAUpdaters() {
    Map<String, Map<String, PropertyUpdater>> highAvailabilityUpdaters = new HashMap<String, Map<String, PropertyUpdater>>();
    Map<String, PropertyUpdater> hdfsSiteUpdatersForAvailability = new HashMap<String, PropertyUpdater>();
    highAvailabilityUpdaters.put("hdfs-site", hdfsSiteUpdatersForAvailability);

    Map<String, String> hdfsSiteConfig = properties.get("hdfs-site");
    // generate the property names based on the current HA config for the NameNode deployments
    for (String nameService : parseNameServices(hdfsSiteConfig)) {
      for (String nameNode : parseNameNodes(nameService, hdfsSiteConfig)) {
        final String httpsPropertyName = "dfs.namenode.https-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(httpsPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
        final String httpPropertyName = "dfs.namenode.http-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(httpPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
        final String rpcPropertyName = "dfs.namenode.rpc-address." + nameService + "." + nameNode;
        hdfsSiteUpdatersForAvailability.put(rpcPropertyName, new SingleHostTopologyUpdater("NAMENODE"));
      }
    }
    return highAvailabilityUpdaters;
  }

  /**
   * Convenience function to determine if NameNode HA is enabled.
   *
   * @return true if NameNode HA is enabled
   *         false if NameNode HA is not enabled
   */
  boolean isNameNodeHAEnabled() {
    return isNameNodeHAEnabled(properties);
  }

  /**
   * Static convenience function to determine if NameNode HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if NameNode HA is enabled
   *         false if NameNode HA is not enabled
   */
  static boolean isNameNodeHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hdfs-site") && configProperties.get("hdfs-site").containsKey("dfs.nameservices");
  }


  /**
   * Static convenience function to determine if Yarn ResourceManager HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if Yarn ResourceManager HA is enabled
   *         false if Yarn ResourceManager HA is not enabled
   */
  static boolean isYarnResourceManagerHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("yarn-site") && configProperties.get("yarn-site").containsKey("yarn.resourcemanager.ha.enabled")
      && configProperties.get("yarn-site").get("yarn.resourcemanager.ha.enabled").equals("true");
  }

  /**
   * Static convenience function to determine if Oozie HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if Oozie HA is enabled
   *         false if Oozie HA is not enabled
   */
  static boolean isOozieServerHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("oozie-site") && configProperties.get("oozie-site").containsKey("oozie.services.ext")
      && configProperties.get("oozie-site").get("oozie.services.ext").contains("org.apache.oozie.service.ZKLocksService");
  }

  /**
   * Static convenience function to determine if HiveServer HA is enabled
   * @param configProperties configuration properties for this cluster
   * @return true if HiveServer HA is enabled
   *         false if HiveServer HA is not enabled
   */
  static boolean isHiveServerHAEnabled(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hive-site") && configProperties.get("hive-site").containsKey("hive.server2.support.dynamic.service.discovery")
      && configProperties.get("hive-site").get("hive.server2.support.dynamic.service.discovery").equals("true");
  }

  /**
   * Convenience method to examine the current configuration, to determine
   * if the hostname of the initial active namenode in an HA deployment has
   * been included.
   *
   * @param configProperties the configuration for this cluster
   * @return true if the initial active namenode property has been configured
   *         false if the initial active namenode property has not been configured
   */
  static boolean isNameNodeHAInitialActiveNodeSet(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hadoop-env") && configProperties.get("hadoop-env").containsKey("dfs_ha_initial_namenode_active");
  }


  /**
   * Convenience method to examine the current configuration, to determine
   * if the hostname of the initial standby namenode in an HA deployment has
   * been included.
   *
   * @param configProperties the configuration for this cluster
   * @return true if the initial standby namenode property has been configured
   *         false if the initial standby namenode property has not been configured
   */
  static boolean isNameNodeHAInitialStandbyNodeSet(Map<String, Map<String, String>> configProperties) {
    return configProperties.containsKey("hadoop-env") && configProperties.get("hadoop-env").containsKey("dfs_ha_initial_namenode_standby");
  }


  /**
   * Parses out the list of nameservices associated with this HDFS configuration.
   *
   * @param properties config properties for this cluster
   *
   * @return array of Strings that indicate the nameservices for this cluster
   */
  static String[] parseNameServices(Map<String, String> properties) {
    final String nameServices = properties.get("dfs.nameservices");
    return splitAndTrimStrings(nameServices);
  }

  /**
   * Parses out the list of name nodes associated with a given HDFS
   *   NameService, based on a given HDFS configuration.
   *
   * @param nameService the nameservice used for this parsing
   * @param properties config properties for this cluster
   *
   * @return array of Strings that indicate the name nodes associated
   *           with this nameservice
   */
  static String[] parseNameNodes(String nameService, Map<String, String> properties) {
    final String nameNodes = properties.get("dfs.ha.namenodes." + nameService);
    return splitAndTrimStrings(nameNodes);
  }

  /**
   * Update single host topology configuration properties for blueprint export.
   *
   * @param hostGroups  cluster export
   * @param updaters    registered updaters
   */
  private void doSingleHostExportUpdate(Collection<? extends HostGroup> hostGroups,
                                        Map<String, Map<String, PropertyUpdater>> updaters) {

    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        boolean matchedHost = false;

        Map<String, String> typeProperties = properties.get(type);
        if (typeProperties != null && typeProperties.containsKey(propertyName)) {
          String propValue = typeProperties.get(propertyName);
          for (HostGroup group : hostGroups) {
            Collection<String> hosts = group.getHostInfo();
            for (String host : hosts) {
              //todo: need to use regular expression to avoid matching a host which is a superset.
              if (propValue.contains(host)) {
                matchedHost = true;
                typeProperties.put(propertyName, propValue.replace(
                    host, "%HOSTGROUP::" + group.getName() + "%"));
                break;
              }
            }
            if (matchedHost) {
              break;
            }
          }
          // remove properties that do not contain hostnames,
          // except in the case of HA-related properties, that
          // can contain nameservice references instead of hostnames (Fix for Bug AMBARI-7458).
          // also will not remove properties that reference the special 0.0.0.0 network
          // address or properties with undefined hosts
          if (! matchedHost &&
              ! isNameServiceProperty(propertyName) &&
              ! isSpecialNetworkAddress(propValue)  &&
              ! isUndefinedAddress(propValue)) {

            typeProperties.remove(propertyName);
          }
        }
      }
    }
  }

  /**
   * Determines if a given property name's value can include
   *   nameservice references instead of host names.
   *
   * @param propertyName name of the property
   *
   * @return true if this property can support using nameservice names
   *         false if this property cannot support using nameservice names
   */
  private static boolean isNameServiceProperty(String propertyName) {
    return configPropertiesWithHASupport.contains(propertyName);
  }

  /**
   * Queries a property value to determine if the value contains
   *   a host address with all zeros (0.0.0.0).  This is a special
   *   address that signifies that the service is available on
   *   all network interfaces on a given machine.
   *
   * @param propertyValue the property value to inspect
   *
   * @return true if the 0.0.0.0 address is included in this string
   *         false if the 0.0.0.0 address is not included in this string
   */
  private static boolean isSpecialNetworkAddress(String propertyValue) {
    return propertyValue.contains("0.0.0.0");
  }

  /**
   * Determine if a property has an undefined host.
   *
   * @param propertyValue  property value
   *
   * @return true if the property value contains "undefined"
   */
  private static boolean isUndefinedAddress(String propertyValue) {
    return propertyValue.contains("undefined");
  }

  /**
   * Update multi host topology configuration properties for blueprint export.
   *
   * @param hostGroups  cluster host groups
   * @param updaters    registered updaters
   */
  private void doMultiHostExportUpdate(Collection<? extends HostGroup> hostGroups,
                                       Map<String, Map<String, PropertyUpdater>> updaters) {

    for (Map.Entry<String, Map<String, PropertyUpdater>> entry : updaters.entrySet()) {
      String type = entry.getKey();
      for (String propertyName : entry.getValue().keySet()) {
        Map<String, String> typeProperties = properties.get(type);
        if (typeProperties != null && typeProperties.containsKey(propertyName)) {
          String propValue = typeProperties.get(propertyName);
          for (HostGroup group : hostGroups) {
            Collection<String> hosts = group.getHostInfo();
            for (String host : hosts) {
              propValue = propValue.replaceAll(host + "\\b", "%HOSTGROUP::" + group.getName() + "%");
            }
          }
          Collection<String> addedGroups = new HashSet<String>();
          String[] toks = propValue.split(",");
          boolean inBrackets = propValue.startsWith("[");

          StringBuilder sb = new StringBuilder();
          if (inBrackets) {
            sb.append('[');
          }
          boolean firstTok = true;
          for (String tok : toks) {
            tok = tok.replaceAll("[\\[\\]]", "");

            if (addedGroups.add(tok)) {
              if (! firstTok) {
                sb.append(',');
              }
              sb.append(tok);
            }
            firstTok = false;
          }

          if (inBrackets) {
            sb.append(']');
          }
          typeProperties.put(propertyName, sb.toString());
        }
      }
    }
  }

  /**
   * Get host groups which contain a component.
   *
   * @param component   component name
   * @param hostGroups  collection of host groups to check
   *
   * @return collection of host groups which contain the specified component
   */
  private static Collection<HostGroup> getHostGroupsForComponent(String component,
                                                                 Collection<? extends HostGroup> hostGroups) {

    Collection<HostGroup> resultGroups = new LinkedHashSet<HostGroup>();
    for (HostGroup group : hostGroups ) {
      if (group.getComponents().contains(component)) {
        resultGroups.add(group);
      }
    }
    return resultGroups;
  }

  /**
   * Convert a property value which includes a host group topology token to a physical host.
   *
   * @param hostGroups  cluster host groups
   * @param val         value to be converted
   *
   * @return updated value with physical host name
   */
  private static Collection<String> getHostStrings(Map<String, ? extends HostGroup> hostGroups,
                                                   String val) {

    Collection<String> hosts = new LinkedHashSet<String>();
    Matcher m = HOSTGROUP_PORT_REGEX.matcher(val);
    while (m.find()) {
      String groupName = m.group(1);
      String port = m.group(2);


      HostGroup hostGroup = hostGroups.get(groupName);
      if (hostGroup == null) {
        throw new IllegalArgumentException(
            "Unable to match blueprint host group token to a host group: " + groupName);
      }
      for (String host : hostGroup.getHostInfo()) {
        if (port != null) {
          host += ":" + port;
        }
        hosts.add(host);
      }
    }
    return hosts;
  }

  /**
   * Convenience method for splitting out the HA-related properties, while
   *   also removing leading/trailing whitespace.
   *
   * @param propertyName property name to parse
   *
   * @return an array of Strings that represent the comma-separated
   *         elements in this property
   */
  private static String[] splitAndTrimStrings(String propertyName) {
    List<String> namesWithoutWhitespace = new LinkedList<String>();
    for (String service : propertyName.split(",")) {
      namesWithoutWhitespace.add(service.trim());
    }

    return namesWithoutWhitespace.toArray(new String[namesWithoutWhitespace.size()]);
  }

  /**
   * Provides functionality to update a property value.
   */
  public interface PropertyUpdater {
    /**
     * Update a property value.
     *
     *
     * @param hostGroups      host groups
     * @param propertyName    name of property
     * @param origValue       original value of property
     * @param properties      all properties
     * @param stackDefinition definition of stack used for this cluster
     *                        creation attempt
     *
     * @return new property value
     */
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName, String origValue, Map<String, Map<String, String>> properties, Stack stackDefinition
    );
  }

  /**
   * Topology based updater which replaces the original host name of a property with the host name
   * which runs the associated (master) component in the new cluster.
   */
  static class SingleHostTopologyUpdater implements PropertyUpdater {
    /**
     * Component name
     */
    private String component;

    /**
     * Constructor.
     *
     * @param component  component name associated with the property
     */
    public SingleHostTopologyUpdater(String component) {
      this.component = component;
    }

    /**
     * Update the property with the new host name which runs the associated component.
     *
     *
     * @param hostGroups       host groups
     * @param propertyName    name of property
     * @param origValue        original value of property
     * @param properties       all properties
     * @param stackDefinition  stack used for cluster creation
     *
     * @return updated property value with old host name replaced by new host name
     */
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         Stack stackDefinition)  {

      Matcher m = HOSTGROUP_REGEX.matcher(origValue);
      if (m.find()) {
        String hostGroupName = m.group(1);
        HostGroup hostGroup = hostGroups.get(hostGroupName);
        //todo: ensure > 0 hosts (is this necessary)
        return origValue.replace(m.group(0), hostGroup.getHostInfo().iterator().next());
      } else {
        Collection<HostGroup> matchingGroups = getHostGroupsForComponent(component, hostGroups.values());
        if (matchingGroups.size() == 1) {
          return origValue.replace("localhost", matchingGroups.iterator().next().getHostInfo().iterator().next());
        } else {
          Cardinality cardinality = stackDefinition.getCardinality(component);
          // if no matching host groups are found for a component whose configuration
          // is handled by this updater, check the stack first to determine if
          // zero is a valid cardinality for this component.  This is necessary
          // in the case of a component in "technical preview" status, since it
          // may be valid to have 0 or 1 instances of such a component in the cluster
          if (matchingGroups.isEmpty() && cardinality.isValidCount(0)) {
            return origValue;
          } else {
            if (isNameNodeHAEnabled(properties) && isComponentNameNode() && (matchingGroups.size() == 2)) {
              // if this is the defaultFS property, it should reflect the nameservice name,
              // rather than a hostname (used in non-HA scenarios)
              if (properties.containsKey("core-site") && properties.get("core-site").get("fs.defaultFS").equals(origValue)) {
                return origValue;
              }

              if (properties.containsKey("hbase-site") && properties.get("hbase-site").get("hbase.rootdir").equals(origValue)) {
                // hbase-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return origValue;
              }

              if (properties.containsKey("accumulo-site") && properties.get("accumulo-site").get("instance.volumes").equals(origValue)) {
                // accumulo-site's reference to the namenode is handled differently in HA mode, since the
                // reference must point to the logical nameservice, rather than an individual namenode
                return origValue;
              }

              if (!origValue.contains("localhost")) {
                // if this NameNode HA property is a FDQN, then simply return it
                return origValue;
              }

            }

            if (isNameNodeHAEnabled(properties) && isComponentSecondaryNameNode() && (matchingGroups.isEmpty())) {
              // if HDFS HA is enabled, then no replacement is necessary for properties that refer to the SECONDARY_NAMENODE
              // eventually this type of information should be encoded in the stacks
              return origValue;
            }

            if (isYarnResourceManagerHAEnabled(properties) && isComponentResourceManager() && (matchingGroups.size() == 2)) {
              if (!origValue.contains("localhost")) {
                // if this Yarn property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isOozieServerHAEnabled(properties)) && isComponentOozieServer() && (matchingGroups.size() > 1))     {
              if (!origValue.contains("localhost")) {
                // if this Oozie property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isHiveServerHAEnabled(properties)) && isComponentHiveServer() && (matchingGroups.size() > 1)) {
              if (!origValue.contains("localhost")) {
                // if this Hive property is a FQDN, then simply return it
                return origValue;
              }
            }

            if ((isComponentHiveMetaStoreServer()) && matchingGroups.size() > 1) {
              if (!origValue.contains("localhost")) {
                // if this Hive MetaStore property is a FQDN, then simply return it
                return origValue;
              }
            }


            throw new IllegalArgumentException("Unable to update configuration property " + "'" + propertyName + "'"+ " with topology information. " +
              "Component '" + component + "' is not mapped to any host group or is mapped to multiple groups.");
          }
        }
      }
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an HDFS NameNode
     *
     * @return true if the component associated is a NameNode
     *         false if the component is not a NameNode
     */
    private boolean isComponentNameNode() {
      return component.equals("NAMENODE");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an HDFS Secondary NameNode
     *
     * @return true if the component associated is a Secondary NameNode
     *         false if the component is not a Secondary NameNode
     */
    private boolean isComponentSecondaryNameNode() {
      return component.equals("SECONDARY_NAMENODE");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Yarn ResourceManager
     *
     * @return true if the component associated is a Yarn ResourceManager
     *         false if the component is not a Yarn ResourceManager
     */
    private boolean isComponentResourceManager() {
      return component.equals("RESOURCEMANAGER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is an Oozie Server
     *
     * @return true if the component associated is an Oozie Server
     *         false if the component is not an Oozie Server
     */
    private boolean isComponentOozieServer() {
      return component.equals("OOZIE_SERVER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Hive Server
     *
     * @return true if the component associated is a Hive Server
     *         false if the component is not a Hive Server
     */
    private boolean isComponentHiveServer() {
      return component.equals("HIVE_SERVER");
    }

    /**
     * Utility method to determine if the component associated with this updater
     * instance is a Hive MetaStore Server
     *
     * @return true if the component associated is a Hive MetaStore Server
     *         false if the component is not a Hive MetaStore Server
     */
    private boolean isComponentHiveMetaStoreServer() {
      return component.equals("HIVE_METASTORE");
    }

    /**
     * Provides access to the name of the component associated
     *   with this updater instance.
     *
     * @return component name for this updater
     */
    public String getComponentName() {
      return component;
    }
  }

  /**
   * Extension of SingleHostTopologyUpdater that supports the
   * notion of an optional service.  An example: the Storm
   * service has config properties that require the location
   * of the Ganglia server when Ganglia is deployed, but Storm
   * should also start properly without Ganglia.
   *
   * This updater detects the case when the specified component
   * is not found, and returns the original property value.
   *
   */
  private static class OptionalSingleHostTopologyUpdater extends SingleHostTopologyUpdater {

    public OptionalSingleHostTopologyUpdater(String component) {
      super(component);
    }

    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups, String propertyName, String origValue, Map<String, Map<String, String>> properties, Stack stackDefinition) {
      try {
        return super.updateForClusterCreate(hostGroups, propertyName, origValue, properties, stackDefinition);
      } catch (IllegalArgumentException illegalArgumentException) {
        // return the original value, since the optional component is not available in this cluster
        return origValue;
      }
    }
  }

  /**
   * Topology based updater which replaces the original host name of a database property with the host name
   * where the DB is deployed in the new cluster.  If an existing database is specified, the original property
   * value is returned.
   */
  private static class DBTopologyUpdater extends SingleHostTopologyUpdater {
    /**
     * Property type (global, core-site ...) for property which is used to determine if DB is external.
     */
    private final String configPropertyType;

    /**
     * Name of property which is used to determine if DB is new or existing (exernal).
     */
    private final String conditionalPropertyName;

    /**
     * Constructor.
     *
     * @param component                component to get hot name if new DB
     * @param conditionalPropertyType  config type of property used to determine if DB is external
     * @param conditionalPropertyName  name of property which is used to determine if DB is external
     */
    private DBTopologyUpdater(String component, String conditionalPropertyType,
                              String conditionalPropertyName) {
      super(component);
      configPropertyType = conditionalPropertyType;
      this.conditionalPropertyName = conditionalPropertyName;
    }

    /**
     * If database is a new managed database, update the property with the new host name which
     * runs the associated component.  If the database is external (non-managed), return the
     * original value.
     *
     *
     * @param hostGroups       host groups
     * @param origValue        original value of property
     * @param properties       all properties
     * @param stackDefinition  stack used for cluster creation
     *
     * @return updated property value with old host name replaced by new host name or original value
     *         if the database is external
     */
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName,
                                         String origValue, Map<String, Map<String, String>> properties,
                                         Stack stackDefinition) {

      if (isDatabaseManaged(properties)) {
        return super.updateForClusterCreate(hostGroups, propertyName, origValue, properties, stackDefinition);
      } else {
        return origValue;
      }
    }

    /**
     * Determine if database is managed, meaning that it is a component in the cluster topology.
     *
     * @return true if the DB is managed; false otherwise
     */
    private boolean isDatabaseManaged(Map<String, Map<String, String>> properties) {
      // conditional property should always exist since it is required to be specified in the stack
      return properties.get(configPropertyType).
          get(conditionalPropertyName).startsWith("New");
    }
  }

  /**
   * Topology based updater which replaces original host names (possibly more than one) contained in a property
   * value with the host names which runs the associated component in the new cluster.
   */
  private static class MultipleHostTopologyUpdater implements PropertyUpdater {


    private static final Character DEFAULT_SEPARATOR = ',';

    /**
     * Component name
     */
    private final String component;

    /**
     * Separator for multiple property values
     */
    private final Character separator;

    /**
     * Flag to determine if a URL scheme detected as
     * a prefix in the property should be repeated across
     * all hosts in the property
     */
    private final boolean usePrefixForEachHost;

    private final Set<String> setOfKnownURLSchemes = Collections.singleton("thrift://");

    /**
     * Constructor.
     *
     * @param component  component name associated with the property
     */
    public MultipleHostTopologyUpdater(String component) {
      this(component, DEFAULT_SEPARATOR, false);
    }

    /**
     * Constructor
     *
     * @param component component name associated with this property
     * @param separator the separator character to use when multiple hosts
     *                  are specified in a property or URL
     */
    public MultipleHostTopologyUpdater(String component, Character separator, boolean userPrefixForEachHost) {
      this.component = component;
      this.separator = separator;
      this.usePrefixForEachHost = userPrefixForEachHost;
    }

    /**
     * Update all host names included in the original property value with new host names which run the associated
     * component.
     *
     *
     * @param hostGroups       host groups
     *
     * @param origValue        original value of property
     * @param properties       all properties
     * @param stackDefinition  stack used for cluster creation
     *
     * @return updated property value with old host names replaced by new host names
     */
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         Stack stackDefinition) {
      StringBuilder sb = new StringBuilder();

      if (!origValue.contains("%HOSTGROUP") &&
        (!origValue.contains("localhost"))) {
        // this property must contain FQDNs specified directly by the user
        // of the Blueprint, so the processor should not attempt to update them
        return origValue;
      }

      String prefix = null;
      Collection<String> hostStrings = getHostStrings(hostGroups, origValue);
      if (hostStrings.isEmpty()) {
        //default non-exported original value
        String port = null;
        for (String urlScheme : setOfKnownURLSchemes) {
          if (origValue.startsWith(urlScheme)) {
            prefix = urlScheme;
          }
        }

        if (prefix != null) {
          String valueWithoutPrefix = origValue.substring(prefix.length());
          port = calculatePort(valueWithoutPrefix);
          sb.append(prefix);
        } else {
          port = calculatePort(origValue);
        }


        Collection<HostGroup> matchingGroups = getHostGroupsForComponent(component, hostGroups.values());
        for (HostGroup group : matchingGroups) {
          for (String host : group.getHostInfo()) {
            if (port != null) {
              host += ":" + port;
            }
            hostStrings.add(host);
          }
        }
      }



      String suffix = null;

      // parse out prefix if one exists
      Matcher matcher = HOSTGROUP_PORT_REGEX.matcher(origValue);
      if (matcher.find()) {
        int indexOfStart = matcher.start();
        // handle the case of a YAML config property
        if ((indexOfStart > 0) && (!origValue.substring(0, indexOfStart).equals("['"))) {
          // append prefix before adding host names
          prefix = origValue.substring(0, indexOfStart);
          sb.append(prefix);
        }

        // parse out suffix if one exists
        int indexOfEnd = -1;
        while (matcher.find()) {
          indexOfEnd = matcher.end();
        }

        if ((indexOfEnd > -1) && (indexOfEnd < (origValue.length() - 1))) {
          suffix = origValue.substring(indexOfEnd);
        }

      }

      // add hosts to property, using the specified separator
      boolean firstHost = true;
      for (String host : hostStrings) {
        if (!firstHost) {
          sb.append(separator);
          // support config properties that use a list of full URIs
          if (usePrefixForEachHost && (prefix != null)) {
            sb.append(prefix);
          }
        } else {
          firstHost = false;
        }



        sb.append(host);
      }

      if ((suffix != null) && (!suffix.equals("']"))) {
        sb.append(suffix);
      }


      return sb.toString();
    }

    private static String calculatePort(String origValue) {
      if (origValue.contains(":")) {
        //todo: currently assuming all hosts are using same port
        return origValue.substring(origValue.indexOf(":") + 1);
      }

      return null;
    }
  }

  /**
   * Updater which appends "m" to the original property value.
   * For example, "1024" would be updated to "1024m".
   */
  private static class MPropertyUpdater implements PropertyUpdater {
    /**
     * Append 'm' to the original property value if it doesn't already exist.
     *
     *
     * @param hostGroups       host groups
     * @param origValue        original value of property
     * @param properties       all properties
     * @param stackDefinition  stack used for cluster creation
     *
     * @return property with 'm' appended
     */
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName,
                                         String origValue, Map<String,
                                         Map<String, String>> properties,
                                         Stack stackDefinition) {

      return origValue.endsWith("m") ? origValue : origValue + 'm';
    }
  }

  /**
   * Class to facilitate special formatting needs of property values.
   */
  private abstract static class AbstractPropertyValueDecorator implements PropertyUpdater {
    PropertyUpdater propertyUpdater;

    /**
     * Constructor.
     *
     * @param propertyUpdater  wrapped updater
     */
    public AbstractPropertyValueDecorator(PropertyUpdater propertyUpdater) {
      this.propertyUpdater = propertyUpdater;
    }

    /**
     * Return decorated form of the updated input property value.
     *
     * @param hostGroupMap     map of host group name to HostGroup
     * @param origValue        original value of property
     * @param properties       all properties
     * @param stackDefinition  stack used for cluster creation
     *
     * @return Formatted output string
     */
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroupMap,
                                         String propertyName,
                                         String origValue,
                                         Map<String, Map<String, String>> properties,
                                         Stack stackDefinition) {

      return doFormat(propertyUpdater.updateForClusterCreate(hostGroupMap, propertyName, origValue, properties, stackDefinition));
    }

    /**
     * Transform input string to required output format.
     *
     * @param originalValue  original value of property
     *
     * @return formatted output string
     */
    public abstract String doFormat(String originalValue);
  }

  /**
   * Return properties of the form ['value']
   */
  private static class YamlMultiValuePropertyDecorator extends AbstractPropertyValueDecorator {

    public YamlMultiValuePropertyDecorator(PropertyUpdater propertyUpdater) {
      super(propertyUpdater);
    }

    /**
     * Format input String of the form, str1,str2 to ['str1','str2']
     *
     * @param origValue  input string
     *
     * @return formatted string
     */
    @Override
    public String doFormat(String origValue) {
      StringBuilder sb = new StringBuilder();
      if (origValue != null) {
        sb.append("[");
        boolean isFirst = true;
        for (String value : origValue.split(",")) {
          if (!isFirst) {
            sb.append(",");
          } else {
            isFirst = false;
          }
          sb.append("'");
          sb.append(value);
          sb.append("'");
        }
        sb.append("]");
      }
      return sb.toString();
    }
  }

  /**
   * PropertyUpdater implementation that will always return the original
   *   value for the updateForClusterCreate() method.
   *   This updater type should only be used in cases where a given
   *   property requires no updates, but may need to be considered
   *   during the Blueprint export process.
   */
  private static class OriginalValuePropertyUpdater implements PropertyUpdater {
    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups,
                                         String propertyName, String origValue,
                                         Map<String, Map<String, String>> properties,
                                         Stack stackDefinition) {
      // always return the original value, since these properties do not require update handling
      return origValue;
    }
  }


  /**
   * Custom PropertyUpdater that handles the parsing and updating of the
   * "templeton.hive.properties" configuration property for WebHCat.
   * This particular configuration property uses a format of
   * comma-separated key/value pairs.  The Values in the case of the
   * hive.metastores.uri property can also contain commas, and so character
   * escaping with a backslash (\) must take place during substitution.
   *
   */
  private static class TempletonHivePropertyUpdater implements PropertyUpdater {

    private Map<String, PropertyUpdater> mapOfKeysToUpdaters =
      new HashMap<String, PropertyUpdater>();

    TempletonHivePropertyUpdater() {
      // the only known property that requires hostname substitution is hive.metastore.uris,
      // but this updater should be flexible enough for other properties in the future.
      mapOfKeysToUpdaters.put("hive.metastore.uris", new MultipleHostTopologyUpdater("HIVE_METASTORE", ',', true));
    }

    @Override
    public String updateForClusterCreate(Map<String, ? extends HostGroup> hostGroups, String propertyName, String origValue, Map<String, Map<String, String>> properties, Stack stackDefinition) {
      // short-circuit out any custom property values defined by the deployer
      if (!origValue.contains("%HOSTGROUP") &&
        (!origValue.contains("localhost"))) {
        // this property must contain FQDNs specified directly by the user
        // of the Blueprint, so the processor should not attempt to update them
        return origValue;
      }

      StringBuffer updatedResult = new StringBuffer();

      // split out the key/value pairs
      String[] keyValuePairs = origValue.split(",");
      boolean firstValue = true;
      for (String keyValuePair : keyValuePairs) {
        if (!firstValue) {
          updatedResult.append(",");
        } else {
          firstValue = false;
        }

        String key = keyValuePair.split("=")[0];
        if (mapOfKeysToUpdaters.containsKey(key)) {
          String result = mapOfKeysToUpdaters.get(key).updateForClusterCreate(hostGroups, key, keyValuePair.split("=")[1], properties, stackDefinition);
          // append the internal property result, escape out any commas in the internal property,
          // this is required due to the specific syntax of templeton.hive.properties
          updatedResult.append(key + "=" + result.replaceAll(",", Matcher.quoteReplacement("\\,")));
        } else {
          updatedResult.append(keyValuePair);
        }
      }

      return updatedResult.toString();
    }
  }

  /**
   * Register updaters for configuration properties.
   */
  static {

    allUpdaters.add(singleHostTopologyUpdaters);
    allUpdaters.add(multiHostTopologyUpdaters);
    allUpdaters.add(dbHostTopologyUpdaters);
    allUpdaters.add(mPropertyUpdaters);

    Map<String, PropertyUpdater> hdfsSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> mapredSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> coreSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hbaseSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> yarnSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hiveSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieSiteOriginalValueMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> stormSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> accumuloSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> falconStartupPropertiesMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> kafkaBrokerMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> mapredEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hadoopEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hbaseEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> hiveEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieEnvMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> oozieEnvOriginalValueMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiWebhcatSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHbaseSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiStormSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiCoreSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHdfsSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiHiveSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiKafkaBrokerMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiSliderClientMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiYarnSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiOozieSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> multiAccumuloSiteMap = new HashMap<String, PropertyUpdater>();
    Map<String, PropertyUpdater> dbHiveSiteMap = new HashMap<String, PropertyUpdater>();


    singleHostTopologyUpdaters.put("hdfs-site", hdfsSiteMap);
    singleHostTopologyUpdaters.put("mapred-site", mapredSiteMap);
    singleHostTopologyUpdaters.put("core-site", coreSiteMap);
    singleHostTopologyUpdaters.put("hbase-site", hbaseSiteMap);
    singleHostTopologyUpdaters.put("yarn-site", yarnSiteMap);
    singleHostTopologyUpdaters.put("hive-site", hiveSiteMap);
    singleHostTopologyUpdaters.put("oozie-site", oozieSiteMap);
    singleHostTopologyUpdaters.put("storm-site", stormSiteMap);
    singleHostTopologyUpdaters.put("accumulo-site", accumuloSiteMap);
    singleHostTopologyUpdaters.put("falcon-startup.properties", falconStartupPropertiesMap);
    singleHostTopologyUpdaters.put("hive-env", hiveEnvMap);
    singleHostTopologyUpdaters.put("oozie-env", oozieEnvMap);
    singleHostTopologyUpdaters.put("kafka-broker", kafkaBrokerMap);

    mPropertyUpdaters.put("hadoop-env", hadoopEnvMap);
    mPropertyUpdaters.put("hbase-env", hbaseEnvMap);
    mPropertyUpdaters.put("mapred-env", mapredEnvMap);

    multiHostTopologyUpdaters.put("webhcat-site", multiWebhcatSiteMap);
    multiHostTopologyUpdaters.put("hbase-site", multiHbaseSiteMap);
    multiHostTopologyUpdaters.put("storm-site", multiStormSiteMap);
    multiHostTopologyUpdaters.put("core-site", multiCoreSiteMap);
    multiHostTopologyUpdaters.put("hdfs-site", multiHdfsSiteMap);
    multiHostTopologyUpdaters.put("hive-site", multiHiveSiteMap);
    multiHostTopologyUpdaters.put("kafka-broker", multiKafkaBrokerMap);
    multiHostTopologyUpdaters.put("slider-client", multiSliderClientMap);
    multiHostTopologyUpdaters.put("yarn-site", multiYarnSiteMap);
    multiHostTopologyUpdaters.put("oozie-site", multiOozieSiteMap);
    multiHostTopologyUpdaters.put("accumulo-site", multiAccumuloSiteMap);

    dbHostTopologyUpdaters.put("hive-site", dbHiveSiteMap);

    removePropertyUpdaters.put("oozie-env", oozieEnvOriginalValueMap);
    removePropertyUpdaters.put("oozie-site", oozieSiteOriginalValueMap);

    // NAMENODE
    hdfsSiteMap.put("dfs.http.address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.https.address", new SingleHostTopologyUpdater("NAMENODE"));
    coreSiteMap.put("fs.default.name", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.http-address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.https-address", new SingleHostTopologyUpdater("NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.rpc-address", new SingleHostTopologyUpdater("NAMENODE"));
    coreSiteMap.put("fs.defaultFS", new SingleHostTopologyUpdater("NAMENODE"));
    hbaseSiteMap.put("hbase.rootdir", new SingleHostTopologyUpdater("NAMENODE"));
    accumuloSiteMap.put("instance.volumes", new SingleHostTopologyUpdater("NAMENODE"));
    // HDFS shared.edits JournalNode Quorum URL uses semi-colons as separators
    multiHdfsSiteMap.put("dfs.namenode.shared.edits.dir", new MultipleHostTopologyUpdater("JOURNALNODE", ';', false));

    // SECONDARY_NAMENODE
    hdfsSiteMap.put("dfs.secondary.http.address", new SingleHostTopologyUpdater("SECONDARY_NAMENODE"));
    hdfsSiteMap.put("dfs.namenode.secondary.http-address", new SingleHostTopologyUpdater("SECONDARY_NAMENODE"));

    // JOBTRACKER
    mapredSiteMap.put("mapred.job.tracker", new SingleHostTopologyUpdater("JOBTRACKER"));
    mapredSiteMap.put("mapred.job.tracker.http.address", new SingleHostTopologyUpdater("JOBTRACKER"));
    mapredSiteMap.put("mapreduce.history.server.http.address", new SingleHostTopologyUpdater("JOBTRACKER"));


    // HISTORY_SERVER
    yarnSiteMap.put("yarn.log.server.url", new SingleHostTopologyUpdater("HISTORYSERVER"));
    mapredSiteMap.put("mapreduce.jobhistory.webapp.address", new SingleHostTopologyUpdater("HISTORYSERVER"));
    mapredSiteMap.put("mapreduce.jobhistory.address", new SingleHostTopologyUpdater("HISTORYSERVER"));

    // RESOURCEMANAGER
    yarnSiteMap.put("yarn.resourcemanager.hostname", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.resource-tracker.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.webapp.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.scheduler.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));
    yarnSiteMap.put("yarn.resourcemanager.admin.address", new SingleHostTopologyUpdater("RESOURCEMANAGER"));

    // APP_TIMELINE_SERVER
    yarnSiteMap.put("yarn.timeline-service.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));
    yarnSiteMap.put("yarn.timeline-service.webapp.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));
    yarnSiteMap.put("yarn.timeline-service.webapp.https.address", new SingleHostTopologyUpdater("APP_TIMELINE_SERVER"));


    // HIVE_SERVER
    multiHiveSiteMap.put("hive.metastore.uris", new MultipleHostTopologyUpdater("HIVE_METASTORE", ',', true));
    dbHiveSiteMap.put("javax.jdo.option.ConnectionURL",
        new DBTopologyUpdater("MYSQL_SERVER", "hive-env", "hive_database"));
    multiCoreSiteMap.put("hadoop.proxyuser.hive.hosts", new MultipleHostTopologyUpdater("HIVE_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.HTTP.hosts", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.hcat.hosts", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    multiWebhcatSiteMap.put("templeton.hive.properties", new TempletonHivePropertyUpdater());
    multiWebhcatSiteMap.put("templeton.kerberos.principal", new MultipleHostTopologyUpdater("WEBHCAT_SERVER"));
    hiveEnvMap.put("hive_hostname", new SingleHostTopologyUpdater("HIVE_SERVER"));
    multiHiveSiteMap.put("hive.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiHiveSiteMap.put("hive.cluster.delegation.token.store.zookeeper.connectString", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));

    // OOZIE_SERVER
    oozieSiteMap.put("oozie.base.url", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieSiteMap.put("oozie.authentication.kerberos.principal", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieSiteMap.put("oozie.service.HadoopAccessorService.kerberos.principal", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    oozieEnvMap.put("oozie_hostname", new SingleHostTopologyUpdater("OOZIE_SERVER"));
    multiCoreSiteMap.put("hadoop.proxyuser.oozie.hosts", new MultipleHostTopologyUpdater("OOZIE_SERVER"));

    // register updaters for Oozie properties that may point to an external DB
    oozieEnvOriginalValueMap.put("oozie_existing_mysql_host", new OriginalValuePropertyUpdater());
    oozieSiteOriginalValueMap.put("oozie.service.JPAService.jdbc.url", new OriginalValuePropertyUpdater());

    // ZOOKEEPER_SERVER
    multiHbaseSiteMap.put("hbase.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiWebhcatSiteMap.put("templeton.zookeeper.hosts", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiCoreSiteMap.put("ha.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiYarnSiteMap.put("hadoop.registry.zk.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiSliderClientMap.put("slider.zookeeper.quorum", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiKafkaBrokerMap.put("zookeeper.connect", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));
    multiAccumuloSiteMap.put("instance.zookeeper.host", new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER"));

    // STORM
    stormSiteMap.put("nimbus.host", new SingleHostTopologyUpdater("NIMBUS"));
    stormSiteMap.put("worker.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    stormSiteMap.put("supervisor.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    stormSiteMap.put("nimbus.childopts", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));
    multiStormSiteMap.put("storm.zookeeper.servers",
        new YamlMultiValuePropertyDecorator(new MultipleHostTopologyUpdater("ZOOKEEPER_SERVER")));

    // FALCON
    falconStartupPropertiesMap.put("*.broker.url", new SingleHostTopologyUpdater("FALCON_SERVER"));
    falconStartupPropertiesMap.put("*.falcon.service.authentication.kerberos.principal", new SingleHostTopologyUpdater("FALCON_SERVER"));
    falconStartupPropertiesMap.put("*.falcon.http.authentication.kerberos.principal", new SingleHostTopologyUpdater("FALCON_SERVER"));

    // KAFKA
    kafkaBrokerMap.put("kafka.ganglia.metrics.host", new OptionalSingleHostTopologyUpdater("GANGLIA_SERVER"));

    // KNOX
    multiCoreSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiWebhcatSiteMap.put("webhcat.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiOozieSiteMap.put("hadoop.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));
    multiOozieSiteMap.put("oozie.service.ProxyUserService.proxyuser.knox.hosts", new MultipleHostTopologyUpdater("KNOX_GATEWAY"));


    // Required due to AMBARI-4933.  These no longer seem to be required as the default values in the stack
    // are now correct but are left here in case an existing blueprint still contains an old value.
    hadoopEnvMap.put("namenode_heapsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_newsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_maxnewsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_permsize", new MPropertyUpdater());
    hadoopEnvMap.put("namenode_opt_maxpermsize", new MPropertyUpdater());
    hadoopEnvMap.put("dtnode_heapsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_opt_newsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_opt_maxnewsize", new MPropertyUpdater());
    mapredEnvMap.put("jtnode_heapsize", new MPropertyUpdater());
    hbaseEnvMap.put("hbase_master_heapsize", new MPropertyUpdater());
    hbaseEnvMap.put("hbase_regionserver_heapsize", new MPropertyUpdater());
  }
}
