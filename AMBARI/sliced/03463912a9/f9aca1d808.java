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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.*;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceAlreadyExistsException;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.BlueprintDAO;
import org.apache.ambari.server.orm.entities.BlueprintConfigEntity;
import org.apache.ambari.server.orm.entities.BlueprintEntity;
import org.apache.ambari.server.orm.entities.HostGroupEntity;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.ConfigImpl;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;

/**
 * Resource provider for cluster resources.
 */
public class ClusterResourceProvider extends BaseBlueprintProcessor {

  // ----- Property ID constants ---------------------------------------------

  // Clusters
  public static final String CLUSTER_ID_PROPERTY_ID      = PropertyHelper.getPropertyId("Clusters", "cluster_id");
  public static final String CLUSTER_NAME_PROPERTY_ID    = PropertyHelper.getPropertyId("Clusters", "cluster_name");
  protected static final String CLUSTER_VERSION_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "version");
  protected static final String CLUSTER_PROVISIONING_STATE_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "provisioning_state");
  protected static final String CLUSTER_DESIRED_CONFIGS_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "desired_configs");
  protected static final String CLUSTER_DESIRED_SERVICE_CONFIG_VERSIONS_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "desired_service_config_versions");
  protected static final String CLUSTER_TOTAL_HOSTS_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "total_hosts");
  protected static final String CLUSTER_HEALTH_REPORT_PROPERTY_ID = PropertyHelper.getPropertyId("Clusters", "health_report");
  protected static final String BLUEPRINT_PROPERTY_ID = PropertyHelper.getPropertyId(null, "blueprint");
  protected static final String SESSION_ATTRIBUTES_PROPERTY_ID = "session_attributes";

  /**
   * The session attributes property prefix.
   */
  private static final String SESSION_ATTRIBUTES_PROPERTY_PREFIX = SESSION_ATTRIBUTES_PROPERTY_ID + "/";

  /**
   * Request info property ID.  Allow internal getResources call to bypass permissions check.
   */
  public static final String GET_IGNORE_PERMISSIONS_PROPERTY_ID = "get_resource/ignore_permissions";

  /**
   * The cluster primary key properties.
   */
  private static Set<String> pkPropertyIds =
      new HashSet<String>(Arrays.asList(new String[]{CLUSTER_ID_PROPERTY_ID}));

  /**
   * The key property ids for a cluster resource.
   */
  private static Map<Resource.Type, String> keyPropertyIds = new HashMap<Resource.Type, String>();
  static {
    keyPropertyIds.put(Resource.Type.Cluster, CLUSTER_NAME_PROPERTY_ID);
  }

  /**
   * The property ids for a cluster resource.
   */
  private static Set<String> propertyIds = new HashSet<String>();
  static {
    propertyIds.add(CLUSTER_ID_PROPERTY_ID);
    propertyIds.add(CLUSTER_NAME_PROPERTY_ID);
    propertyIds.add(CLUSTER_VERSION_PROPERTY_ID);
    propertyIds.add(CLUSTER_PROVISIONING_STATE_PROPERTY_ID);
    propertyIds.add(CLUSTER_DESIRED_CONFIGS_PROPERTY_ID);
    propertyIds.add(CLUSTER_DESIRED_SERVICE_CONFIG_VERSIONS_PROPERTY_ID);
    propertyIds.add(CLUSTER_TOTAL_HOSTS_PROPERTY_ID);
    propertyIds.add(CLUSTER_HEALTH_REPORT_PROPERTY_ID);
    propertyIds.add(BLUEPRINT_PROPERTY_ID);
    propertyIds.add(SESSION_ATTRIBUTES_PROPERTY_ID);
  }

  /**
   * Maps configuration type (string) to associated properties
   */
  private Map<String, Map<String, String>> mapClusterConfigurations =
      new HashMap<String, Map<String, String>>();
  /**
   * Maps configuration type (string) to property attributes, and their values
   */
  private Map<String, Map<String, Map<String, String>>> mapClusterAttributes =
      new HashMap<String, Map<String, Map<String, String>>>();


  // ----- Constructors ----------------------------------------------------

  /**
   * Create a  new resource provider for the given management controller.
   *
   * @param managementController  the management controller
   */
  ClusterResourceProvider(AmbariManagementController managementController) {
    super(propertyIds, keyPropertyIds, managementController);
  }


  // ----- ResourceProvider ------------------------------------------------

  @Override
  public RequestStatus createResources(Request request)
      throws SystemException,
             UnsupportedPropertyException,
             ResourceAlreadyExistsException,
             NoSuchParentResourceException {

    RequestStatusResponse createResponse = null;
    for (final Map<String, Object> properties : request.getProperties()) {
      if (isCreateFromBlueprint(properties)) {
        createResponse = processBlueprintCreate(properties);
      } else {
        createClusterResource(properties);
      }
    }

    notifyCreate(Resource.Type.Cluster, request);
    return getRequestStatus(createResponse);
  }

  @Override
  public Set<Resource> getResources(Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    final Set<ClusterRequest> requests = new HashSet<ClusterRequest>();

    if (predicate == null) {
      requests.add(getRequest(Collections.<String, Object>emptyMap()));
    } else {
      for (Map<String, Object> propertyMap : getPropertyMaps(predicate)) {
        requests.add(getRequest(propertyMap));
      }
    }
    Set<String> requestedIds = getRequestPropertyIds(request, predicate);

    Set<ClusterResponse> responses = getResources(new Command<Set<ClusterResponse>>() {
      @Override
      public Set<ClusterResponse> invoke() throws AmbariException {
        return getManagementController().getClusters(requests);
      }
    });

    Set<Resource> resources = new HashSet<Resource>();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Found clusters matching getClusters request"
          + ", clusterResponseCount=" + responses.size());
    }

    // Allow internal call to bypass permissions check.
    Map<String, String> requestInfoProperties = request.getRequestInfoProperties();
    boolean ignorePermissions = requestInfoProperties == null ? false :
        Boolean.valueOf(requestInfoProperties.get(GET_IGNORE_PERMISSIONS_PROPERTY_ID));

    for (ClusterResponse response : responses) {

      String clusterName = response.getClusterName();

      Resource resource = new ResourceImpl(Resource.Type.Cluster);
      setResourceProperty(resource, CLUSTER_ID_PROPERTY_ID, response.getClusterId(), requestedIds);
      setResourceProperty(resource, CLUSTER_NAME_PROPERTY_ID, clusterName, requestedIds);
      setResourceProperty(resource, CLUSTER_PROVISIONING_STATE_PROPERTY_ID, response.getProvisioningState(), requestedIds);
      setResourceProperty(resource, CLUSTER_DESIRED_CONFIGS_PROPERTY_ID, response.getDesiredConfigs(), requestedIds);
      setResourceProperty(resource, CLUSTER_DESIRED_SERVICE_CONFIG_VERSIONS_PROPERTY_ID,
        response.getDesiredServiceConfigVersions(), requestedIds);
      setResourceProperty(resource, CLUSTER_TOTAL_HOSTS_PROPERTY_ID, response.getTotalHosts(), requestedIds);
      setResourceProperty(resource, CLUSTER_HEALTH_REPORT_PROPERTY_ID, response.getClusterHealthReport(), requestedIds);

      resource.setProperty(CLUSTER_VERSION_PROPERTY_ID,
          response.getDesiredStackVersion());

      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding ClusterResponse to resource"
            + ", clusterResponse=" + response.toString());
      }
      if (ignorePermissions || includeCluster(clusterName, true)) {
        resources.add(resource);
      }
    }
    return resources;
  }

  @Override
  public RequestStatus updateResources(final Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    final Set<ClusterRequest>   requests = new HashSet<ClusterRequest>();
    RequestStatusResponse       response;

    for (Map<String, Object> requestPropertyMap : request.getProperties()) {
      Set<Map<String, Object>> propertyMaps = getPropertyMaps(requestPropertyMap, predicate);
      for (Map<String, Object> propertyMap : propertyMaps) {
        ClusterRequest clusterRequest = getRequest(propertyMap);
        if (includeCluster(clusterRequest.getClusterName(), false)) {
          requests.add(clusterRequest);
        }
      }
    }
    response = modifyResources(new Command<RequestStatusResponse>() {
      @Override
      public RequestStatusResponse invoke() throws AmbariException {
        return getManagementController().updateClusters(requests, request.getRequestInfoProperties());
      }
    });
    notifyUpdate(Resource.Type.Cluster, request, predicate);

    Set<Resource> associatedResources = null;
    for (ClusterRequest clusterRequest : requests) {
      ClusterResponse updateResults = getManagementController().getClusterUpdateResults(clusterRequest);
      if (updateResults != null) {
        Map<String, Collection<ServiceConfigVersionResponse>> serviceConfigVersions = updateResults.getDesiredServiceConfigVersions();
        if (serviceConfigVersions != null) {
          associatedResources = new HashSet<Resource>();
          for (Collection<ServiceConfigVersionResponse> scvCollection : serviceConfigVersions.values()) {
            for (ServiceConfigVersionResponse serviceConfigVersionResponse : scvCollection) {
              Resource resource = new ResourceImpl(Resource.Type.ServiceConfigVersion);
              resource.setProperty(ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_SERVICE_NAME_PROPERTY_ID,
                serviceConfigVersionResponse.getServiceName());
              resource.setProperty(ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_PROPERTY_ID,
                serviceConfigVersionResponse.getVersion());
              resource.setProperty(ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_NOTE_PROPERTY_ID,
                serviceConfigVersionResponse.getNote());
              resource.setProperty(ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_GROUP_ID_PROPERTY_ID,
                  serviceConfigVersionResponse.getGroupId());
              resource.setProperty(ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_GROUP_NAME_PROPERTY_ID,
                  serviceConfigVersionResponse.getGroupName());
              if (serviceConfigVersionResponse.getConfigurations() != null) {
                resource.setProperty(
                  ServiceConfigVersionResourceProvider.SERVICE_CONFIG_VERSION_CONFIGURATIONS_PROPERTY_ID,
                  serviceConfigVersionResponse.getConfigurations());
              }
              associatedResources.add(resource);
            }
          }

        }
      }
    }


    return getRequestStatus(response, associatedResources);
  }

  @Override
  public RequestStatus deleteResources(Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    for (Map<String, Object> propertyMap : getPropertyMaps(predicate)) {
      final ClusterRequest clusterRequest = getRequest(propertyMap);
      if (includeCluster(clusterRequest.getClusterName(), false)) {
        modifyResources(new Command<Void>() {
          @Override
          public Void invoke() throws AmbariException {
            getManagementController().deleteCluster(clusterRequest);
            return null;
          }
        });
      }
    }
    notifyDelete(Resource.Type.Cluster, predicate);
    return getRequestStatus(null);
  }

  @Override
  protected Set<String> getPKPropertyIds() {
    return pkPropertyIds;
  }

  /**
   * {@inheritDoc}  Overridden to support configuration.
   */
  @Override
  public Set<String> checkPropertyIds(Set<String> propertyIds) {
    Set<String> baseUnsupported = super.checkPropertyIds(propertyIds);

    // extract to own method
    baseUnsupported.remove("blueprint");
    baseUnsupported.remove("host_groups");
    baseUnsupported.remove("default_password");
    baseUnsupported.remove("configurations");

    // Allow property Ids that start with "kerberos_descriptor/"
    Iterator<String> iterator = baseUnsupported.iterator();
    while (iterator.hasNext()) {
      if (iterator.next().startsWith("kerberos_descriptor/")) {
        iterator.remove();
      }
    }

    return checkConfigPropertyIds(baseUnsupported, "Clusters");
  }


  // ----- ClusterResourceProvider -------------------------------------------

  /**
   * Inject the blueprint data access object which is used to obtain blueprint entities.
   *
   * @param dao  blueprint data access object
   */
  public static void init(BlueprintDAO dao, AmbariMetaInfo metaInfo, ConfigHelper ch) {
    blueprintDAO = dao;
    stackInfo    = metaInfo;
    configHelper = ch;
  }


  /**
   * Package-level access for cluster config
   * @return cluster config map
   */
  Map<String, Map<String, String>> getClusterConfigurations() {
    return mapClusterConfigurations;
  }


  // ----- utility methods ---------------------------------------------------

  /**
   * Get a cluster request object from a map of property values.
   *
   * @param properties  the predicate
   *
   * @return the cluster request object
   */
  private ClusterRequest getRequest(Map<String, Object> properties) {
    KerberosDescriptor kerberosDescriptor = new KerberosDescriptor(createKerberosPropertyMap(properties));

    ClusterRequest cr = new ClusterRequest(
        (Long) properties.get(CLUSTER_ID_PROPERTY_ID),
        (String) properties.get(CLUSTER_NAME_PROPERTY_ID),
        (String) properties.get(CLUSTER_PROVISIONING_STATE_PROPERTY_ID),
        (String) properties.get(CLUSTER_VERSION_PROPERTY_ID),
        null,
        kerberosDescriptor,
        getSessionAttributes(properties));

    List<ConfigurationRequest> configRequests = getConfigurationRequests("Clusters", properties);

    ServiceConfigVersionRequest serviceConfigVersionRequest = getServiceConfigVersionRequest("Clusters", properties);

    if (!configRequests.isEmpty())
      cr.setDesiredConfig(configRequests);

    if (serviceConfigVersionRequest != null) {
      cr.setServiceConfigVersionRequest(serviceConfigVersionRequest);
    }

    return cr;
  }

  /**
   * Recursively attempts to "normalize" a property value into either a single Object, a Map or a
   * Collection of items depending on the type of Object that is supplied.
   * <p/>
   * If the supplied value is a Map, attempts to render a Map of keys to "normalized" values. This
   * may yield a Map of Maps or a Map of Collections, or a Map of values.
   * <p/>
   * If the supplied value is a Collection, attempts to render a Collection of Maps, Collections, or values
   * <p/>
   * Else, assumes the value is a simple value
   *
   * @param property an Object to "normalize"
   * @return the normalized object or the input value if it is not a Map or Collection.
   */
  private Object normalizeKerberosProperty(Object property) {
    if (property instanceof Map) {
      Map<?, ?> properties = (Map) property;
      Map<String, Object> map = new HashMap<String, Object>(properties.size());

      for (Map.Entry<?, ?> entry : properties.entrySet()) {
        normalizeKerberosProperty(entry.getKey().toString(), entry.getValue(), map);
      }

      return map;
    } else if (property instanceof Collection) {
      Collection properties = (Collection) property;
      Collection<Object> collection = new ArrayList<Object>(properties.size());

      for (Object item : properties) {
        collection.add(normalizeKerberosProperty(item));
      }

      return collection;
    } else {
      return property;
    }
  }

  /**
   * Recursively attempts to "normalize" a property value into either a single Object, a Map or a
   * Collection of items; and places the result into the supplied Map under a specified key.
   * <p/>
   * See {@link #normalizeKerberosProperty(Object)} for more information "normalizing" a property value
   *
   * If the key (propertyName) indicates a hierarchy by separating names with a '/', the supplied map
   * will be updated to handle the hierarchy. For example, if the propertyName value is "parent/child"
   * then the map will be updated to contain an entry where the key is named "parent" and the value
   * is a Map containing an entry with a name of "child" and value that is the normalized version of
   * the specified value (propertyValue).
   *
   * @param propertyName a String declaring the name of the supplied property value
   * @param propertyValue an Object containing the property value
   * @param map a Map to store the results within
   * @see #normalizeKerberosProperty(Object)
   */
  private void normalizeKerberosProperty(String propertyName, Object propertyValue, Map<String, Object> map) {
    String[] keys = propertyName.split("/");
    Map<String, Object> currentMap = map;

    if (keys.length > 0) {
      for (int i = 0; i < keys.length - 1; i++) {
        String key = keys[i];

        Object value = currentMap.get(key);

        if (value instanceof Map) {
          currentMap = (Map<String, Object>) value;
        } else {
          Map<String, Object> temp = new HashMap<String, Object>();
          currentMap.put(key, temp);
          currentMap = temp;
        }
      }

      currentMap.put(keys[keys.length - 1], normalizeKerberosProperty(propertyValue));
    }
  }

  /**
   * Given a Map of Strings to Objects, attempts to expand all properties into a tree of Maps to
   * effectively represent a Kerberos descriptor.
   *
   * @param properties a Map of properties to process
   * @return a Map containing the expanded hierarchy of data
   * @see #normalizeKerberosProperty(String, Object, java.util.Map)
   */
  private Map<String, Object> createKerberosPropertyMap(Map<String, Object> properties) {
    Map<String, Object> kerberosPropertyMap = new HashMap<String, Object>();

    if (properties != null) {
      for (Map.Entry<String, Object> entry : properties.entrySet()) {
        String key = entry.getKey();
        if (key.startsWith("kerberos_descriptor/")) {
          normalizeKerberosProperty(key.replace("kerberos_descriptor/", ""), entry.getValue(), kerberosPropertyMap);
        }
      }
    }

    return kerberosPropertyMap;
  }

  /**
   * Get the map of session attributes from the given property map.
   *
   * @param properties  the property map from the request
   *
   * @return the map of session attributes
   */
  private Map<String, Object> getSessionAttributes(Map<String, Object> properties) {
    Map<String, Object> sessionAttributes = new HashMap<String, Object>();

    for (Map.Entry<String, Object> entry : properties.entrySet()) {

      String property = entry.getKey();

      if (property.startsWith(SESSION_ATTRIBUTES_PROPERTY_PREFIX)) {
        String attributeName = property.substring(SESSION_ATTRIBUTES_PROPERTY_PREFIX.length());
        sessionAttributes.put(attributeName, entry.getValue());
      }
    }
    return sessionAttributes;
  }

  /**
   * Helper method for creating rollback request
   */
  protected ServiceConfigVersionRequest getServiceConfigVersionRequest(String parentCategory, Map<String, Object> properties) {
    ServiceConfigVersionRequest serviceConfigVersionRequest = null;

    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String absCategory = PropertyHelper.getPropertyCategory(entry.getKey());
      String propName = PropertyHelper.getPropertyName(entry.getKey());

      if (absCategory.startsWith(parentCategory + "/desired_service_config_version")) {
        serviceConfigVersionRequest =
            (serviceConfigVersionRequest ==null ) ? new ServiceConfigVersionRequest() : serviceConfigVersionRequest;

        if (propName.equals("service_name"))
          serviceConfigVersionRequest.setServiceName(entry.getValue().toString());
        else if (propName.equals("service_config_version"))
          serviceConfigVersionRequest.setVersion(Long.valueOf(entry.getValue().toString()));
        else if (propName.equals("service_config_version_note")) {
          serviceConfigVersionRequest.setNote(entry.getValue().toString());
        }
      }
    }
    return serviceConfigVersionRequest;
  }

  /**
   * Determine if the request is a create using a blueprint.
   *
   * @param properties  request properties
   *
   * @return true if request is a create using a blueprint; false otherwise
   */
  private boolean isCreateFromBlueprint(Map<String, Object> properties) {
    return properties.get("blueprint") != null;
  }

  /**
   * Process a create request specifying a blueprint.  This includes creation of all resources,
   * setting of configuration and installing and starting of all services.  The end result of this
   * call will be a running cluster based on the topology and configuration specified in the blueprint.
   *
   * @param properties  request body properties
   *
   * @return asynchronous response information
   *
   * @throws ResourceAlreadyExistsException if cluster already exists
   * @throws SystemException                if an unexpected exception occurs
   * @throws UnsupportedPropertyException   if an invalid property is specified in the request
   * @throws NoSuchParentResourceException  if a necessary parent resource doesn't exist
   */
  @SuppressWarnings("unchecked")
  private RequestStatusResponse processBlueprintCreate(Map<String, Object> properties)
      throws ResourceAlreadyExistsException, SystemException, UnsupportedPropertyException,
      NoSuchParentResourceException {

    String blueprintName = (String) properties.get(BLUEPRINT_PROPERTY_ID);

    LOG.info("Creating Cluster '" + properties.get(CLUSTER_NAME_PROPERTY_ID) +
        "' based on blueprint '" + blueprintName + "'.");

    //todo: build up a proper topology object
    BlueprintEntity blueprint = getExistingBlueprint(blueprintName);
    Stack stack = parseStack(blueprint);

    Map<String, HostGroupImpl> blueprintHostGroups = parseBlueprintHostGroups(blueprint, stack);
    applyRequestInfoToHostGroups(properties, blueprintHostGroups);
    Collection<Map<String, String>> configOverrides = (Collection<Map<String, String>>)properties.get("configurations");

    String message = null;
    for (BlueprintConfigEntity blueprintConfig: blueprint.getConfigurations()){
      if(blueprintConfig.getType().equals("global")){
        message = "WARNING: Global configurations are deprecated, please use *-env";
        break;
      }
    }

    processConfigurations(processBlueprintConfigurations(blueprint, configOverrides),
        processBlueprintAttributes(blueprint), stack, blueprintHostGroups);
    validatePasswordProperties(blueprint, blueprintHostGroups, (String) properties.get("default_password"));

    String clusterName = (String) properties.get(CLUSTER_NAME_PROPERTY_ID);
    createClusterResource(buildClusterResourceProperties(stack, clusterName));
    setConfigurationsOnCluster(clusterName, stack, blueprintHostGroups);

    Set<String> services = getServicesToDeploy(stack, blueprintHostGroups);

    createServiceAndComponentResources(blueprintHostGroups, clusterName, services);
    createHostAndComponentResources(blueprintHostGroups, clusterName);

    registerConfigGroups(clusterName, blueprintHostGroups, stack);

    persistInstallStateForUI(clusterName);

    RequestStatusResponse request = ((ServiceResourceProvider) getResourceProvider(Resource.Type.Service)).
        installAndStart(clusterName);

    request.setMessage(message);

    return request;
  }

  /**
   * Validate that all required password properties have been set or that 'default_password' is specified.
   *
   * @param blueprint        associated blueprint entity
   * @param hostGroups       host groups in blueprint
   * @param defaultPassword  specified default password, may be null
   *
   * @throws IllegalArgumentException if required password properties are missing and no
   *                                  default is specified via 'default_password'
   */
  private void validatePasswordProperties(BlueprintEntity blueprint, Map<String, HostGroupImpl> hostGroups,
                                          String defaultPassword) {

    Map<String, Map<String, Collection<String>>> missingPasswords = blueprint.validateConfigurations(
        stackInfo, true);

    Iterator<Map.Entry<String, Map<String, Collection<String>>>> iter;
    for(iter = missingPasswords.entrySet().iterator(); iter.hasNext(); ) {
      Map.Entry<String, Map<String, Collection<String>>> entry = iter.next();
      Map<String, Collection<String>> missingProps = entry.getValue();
      Iterator<Map.Entry<String, Collection<String>>> hostGroupIter;

      for (hostGroupIter = missingProps.entrySet().iterator(); hostGroupIter.hasNext(); ) {
        Map.Entry<String, Collection<String>> hgEntry = hostGroupIter.next();
        String configType = hgEntry.getKey();
        Collection<String> propertySet = hgEntry.getValue();

        for (Iterator<String> propIter = propertySet.iterator(); propIter.hasNext(); ) {
          String property = propIter.next();
          if (isPropertyInConfiguration(mapClusterConfigurations.get(configType), property)){
              propIter.remove();
          } else {
            HostGroupImpl hg = hostGroups.get(entry.getKey());
            if (hg != null && isPropertyInConfiguration(hg.getConfigurationProperties().get(configType), property)) {
              propIter.remove();
            }  else if (setDefaultPassword(defaultPassword, configType, property)) {
              propIter.remove();
            }
          }
        }
        if (propertySet.isEmpty()) {
          hostGroupIter.remove();
        }
      }
      if (entry.getValue().isEmpty()) {
        iter.remove();
      }
    }

    if (! missingPasswords.isEmpty()) {
      throw new IllegalArgumentException("Missing required password properties.  Specify a value for these " +
          "properties in the cluster or host group configurations or include 'default_password' field in request. " +
          missingPasswords);
    }
  }

  /**
   * Attempt to set the default password in cluster configuration for missing password property.
   *
   * @param defaultPassword  default password specified in request, may be null
   * @param configType       configuration type
   * @param property         password property name
   *
   * @return true if password was set, otherwise false.  Currently the password will always be set
   *         unless it is null
   */
  private boolean setDefaultPassword(String defaultPassword, String configType, String property) {
    boolean setDefaultPassword = false;
    Map<String, String> typeProps = mapClusterConfigurations.get(configType);
    if (defaultPassword != null && ! defaultPassword.trim().isEmpty()) {
      // set default password in cluster config
      if (typeProps == null) {
        typeProps = new HashMap<String, String>();
        mapClusterConfigurations.put(configType, typeProps);
      }
      typeProps.put(property, defaultPassword);
      setDefaultPassword = true;
    }
    return setDefaultPassword;
  }

  /**
   * Determine if a specific property is in a configuration.
   *
   * @param props     property map to check
   * @param property  property to check for
   *
   * @return true if the property is contained in the configuration, otherwise false
   */
  private boolean isPropertyInConfiguration(Map<String, String> props, String property) {
    boolean foundProperty = false;
    if (props != null) {
      String val = props.get(property);
      foundProperty = (val != null && ! val.trim().isEmpty());
    }
    return foundProperty;
  }

  /**
   * Create service and component resources.
   *
   * @param blueprintHostGroups  host groups contained in blueprint
   * @param clusterName          cluster name
   * @param services             services to be deployed
   *
   * @throws SystemException                an unexpected exception occurred
   * @throws UnsupportedPropertyException   an unsupported property was specified in the request
   * @throws ResourceAlreadyExistsException attempted to create a service or component that already exists
   * @throws NoSuchParentResourceException  a required parent resource is missing
   */
  private void createServiceAndComponentResources(Map<String, HostGroupImpl> blueprintHostGroups,
                                                  String clusterName, Set<String> services)
                                                  throws SystemException,
                                                         UnsupportedPropertyException,
                                                         ResourceAlreadyExistsException,
                                                         NoSuchParentResourceException {

    Set<Map<String, Object>> setServiceRequestProps = new HashSet<Map<String, Object>>();
    for (String service : services) {
      Map<String, Object> serviceProperties = new HashMap<String, Object>();
      serviceProperties.put(ServiceResourceProvider.SERVICE_CLUSTER_NAME_PROPERTY_ID, clusterName);
      serviceProperties.put(ServiceResourceProvider.SERVICE_SERVICE_NAME_PROPERTY_ID, service);
      setServiceRequestProps.add(serviceProperties);
    }

    Request serviceRequest = new RequestImpl(null, setServiceRequestProps, null, null);
    getResourceProvider(Resource.Type.Service).createResources(serviceRequest);
    createComponentResources(blueprintHostGroups, clusterName, services);
  }

  /**
   * Build the cluster properties necessary for creating a cluster resource.
   *
   * @param stack        associated stack
   * @param clusterName  cluster name
   * @return map of cluster properties used to create a cluster resource
   */
  private Map<String, Object> buildClusterResourceProperties(Stack stack, String clusterName) {
    Map<String, Object> clusterProperties = new HashMap<String, Object>();
    clusterProperties.put(CLUSTER_NAME_PROPERTY_ID, clusterName);
    clusterProperties.put(CLUSTER_VERSION_PROPERTY_ID, stack.getName() + "-" + stack.getVersion());
    return clusterProperties;
  }

  /**
   * Create component resources.
   *
   * @param blueprintHostGroups  host groups specified in blueprint
   * @param clusterName          cluster name
   * @param services             services to be deployed
   *
   * @throws SystemException                an unexpected exception occurred
   * @throws UnsupportedPropertyException   an invalid property was specified
   * @throws ResourceAlreadyExistsException attempt to create a component which already exists
   * @throws NoSuchParentResourceException  a required parent resource is missing
   */
  private void createComponentResources(Map<String, HostGroupImpl> blueprintHostGroups,
                                        String clusterName, Set<String> services)
                                        throws SystemException,
                                               UnsupportedPropertyException,
                                               ResourceAlreadyExistsException,
                                               NoSuchParentResourceException {
    for (String service : services) {
      Set<String> components = new HashSet<String>();
      for (HostGroupImpl hostGroup : blueprintHostGroups.values()) {
        Collection<String> serviceComponents = hostGroup.getComponents(service);
        if (serviceComponents != null && !serviceComponents.isEmpty()) {
          components.addAll(serviceComponents);
        }
      }

      Set<Map<String, Object>> setComponentRequestProps = new HashSet<Map<String, Object>>();
      for (String component : components) {
        Map<String, Object> componentProperties = new HashMap<String, Object>();
        componentProperties.put("ServiceComponentInfo/cluster_name", clusterName);
        componentProperties.put("ServiceComponentInfo/service_name", service);
        componentProperties.put("ServiceComponentInfo/component_name", component);
        setComponentRequestProps.add(componentProperties);
      }
      Request componentRequest = new RequestImpl(null, setComponentRequestProps, null, null);
      ResourceProvider componentProvider = getResourceProvider(Resource.Type.Component);
      componentProvider.createResources(componentRequest);
    }
  }

  /**
   * Set all configurations on the cluster resource.
   *
   * @param clusterName  cluster name
   * @param stack Stack definition object used for this cluster
   * @param blueprintHostGroups host groups defined in the Blueprint for this cluster
   *
   * @throws SystemException an unexpected exception occurred
   */
  private void setConfigurationsOnCluster(String clusterName, Stack stack, Map<String, HostGroupImpl> blueprintHostGroups) throws SystemException {
    List<BlueprintServiceConfigRequest> listofConfigRequests =
      new LinkedList<BlueprintServiceConfigRequest>();

    // create a list of config requests on a per-service basis, in order
    // to properly support the new service configuration versioning mechanism
    // in Ambari
    for (String service : getServicesToDeploy(stack, blueprintHostGroups)) {
      BlueprintServiceConfigRequest blueprintConfigRequest =
        new BlueprintServiceConfigRequest(service);

      for (String serviceConfigType : stack.getConfigurationTypes(service)) {
        // skip handling of cluster-env here
        if (!serviceConfigType.equals("cluster-env")) {
          if (mapClusterConfigurations.containsKey(serviceConfigType)) {
            blueprintConfigRequest.addConfigElement(serviceConfigType,
              mapClusterConfigurations.get(serviceConfigType),
              mapClusterAttributes.get(serviceConfigType));
          }
        }
      }

      listofConfigRequests.add(blueprintConfigRequest);
    }

    // since the stack returns "cluster-env" with each service's config
    // this code needs to ensure that only one ClusterRequest occurs for
    // the global cluster-env configuration
    BlueprintServiceConfigRequest globalConfigRequest =
      new BlueprintServiceConfigRequest("GLOBAL-CONFIG");
    globalConfigRequest.addConfigElement("cluster-env",
      mapClusterConfigurations.get("cluster-env"),
      mapClusterAttributes.get("cluster-env"));
    listofConfigRequests.add(globalConfigRequest);

    try {
      //todo: properly handle non system exceptions
      setConfigurationsOnCluster(clusterName, listofConfigRequests);
    } catch (AmbariException e) {
      throw new SystemException("Unable to set configurations on cluster.", e);
    }

  }


  /**
   * Creates a ClusterRequest for each service that
   *   includes any associated config types and configuration. The Blueprints
   *   implementation will now create one ClusterRequest per service, in order
   *   to comply with the ServiceConfigVersioning framework in Ambari.
   *
   * This method will also send these requests to the management controller.
   *
   * @param clusterName name of cluster
   * @param listOfBlueprintConfigRequests a list of requests to send to the AmbariManagementController.
   *
   * @throws AmbariException upon any error that occurs during updateClusters
   */
  private void setConfigurationsOnCluster(String clusterName, List<BlueprintServiceConfigRequest> listOfBlueprintConfigRequests) throws AmbariException {
    // iterate over services to deploy
    for (BlueprintServiceConfigRequest blueprintConfigRequest : listOfBlueprintConfigRequests) {
      ClusterRequest clusterRequest = null;
      // iterate over the config types associated with this service
      List<ConfigurationRequest> requestsPerService = new LinkedList<ConfigurationRequest>();
      for (BlueprintServiceConfigElement blueprintElement : blueprintConfigRequest.getConfigElements()) {
        Map<String, Object> clusterProperties = new HashMap<String, Object>();
        clusterProperties.put(CLUSTER_NAME_PROPERTY_ID, clusterName);
        clusterProperties.put(CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/type", blueprintElement.getTypeName());
        clusterProperties.put(CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/tag", "1");
        for (Map.Entry<String, String> entry : blueprintElement.getConfiguration().entrySet()) {
          clusterProperties.put(CLUSTER_DESIRED_CONFIGS_PROPERTY_ID +
            "/properties/" + entry.getKey(), entry.getValue());
        }
        if (blueprintElement.getAttributes() != null) {
          for (Map.Entry<String, Map<String, String>> attribute : blueprintElement.getAttributes().entrySet()) {
            String attributeName = attribute.getKey();
            for (Map.Entry<String, String> attributeOccurrence : attribute.getValue().entrySet()) {
              clusterProperties.put(CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/properties_attributes/"
                + attributeName + "/" + attributeOccurrence.getKey(), attributeOccurrence.getValue());
            }
          }
        }

        // only create one cluster request per service, which includes
        // all the configuration types for that service
        if (clusterRequest == null) {
          clusterRequest = new ClusterRequest(
            (Long) clusterProperties.get(CLUSTER_ID_PROPERTY_ID),
            (String) clusterProperties.get(CLUSTER_NAME_PROPERTY_ID),
            (String) clusterProperties.get(CLUSTER_PROVISIONING_STATE_PROPERTY_ID),
            (String) clusterProperties.get(CLUSTER_VERSION_PROPERTY_ID),
            null);
        }

        List<ConfigurationRequest> listOfRequests =
          getConfigurationRequests("Clusters", clusterProperties);
        requestsPerService.addAll(listOfRequests);
      }

      // set total list of config requests, including all config types
      // for this service
      if (clusterRequest != null) {
        clusterRequest.setDesiredConfig(requestsPerService);

        LOG.info("About to send cluster config update request for service = " + blueprintConfigRequest.getServiceName());

        // send the request update for this service as a whole
        getManagementController().updateClusters(
          Collections.singleton(clusterRequest), null);
      } else {
        LOG.error("ClusterRequest should not be null for service = " + blueprintConfigRequest.getServiceName());
      }

    }
  }

  /**
   * Apply the information contained in the cluster request body such as host an configuration properties to
   * the associated blueprint.
   *
   * @param properties           request properties
   * @param blueprintHostGroups  blueprint host groups
   *
   * @throws IllegalArgumentException a host_group in the request doesn't match a host-group in the blueprint
   */
  @SuppressWarnings("unchecked")
  private void applyRequestInfoToHostGroups(Map<String, Object> properties,
                                            Map<String, HostGroupImpl> blueprintHostGroups)
                                            throws IllegalArgumentException {

    @SuppressWarnings("unchecked")
    Collection<Map<String, Object>> hostGroups =
        (Collection<Map<String, Object>>) properties.get("host_groups");

    if (hostGroups == null || hostGroups.isEmpty()) {
      throw new IllegalArgumentException("'host_groups' element must be included in cluster create body");
    }

    // iterate over host groups provided in request body
    for (Map<String, Object> hostGroupProperties : hostGroups) {
      String name = (String) hostGroupProperties.get("name");
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException("Every host_group must include a non-null 'name' property");
      }
      HostGroupImpl hostGroup = blueprintHostGroups.get(name);

      if (hostGroup == null) {
        throw new IllegalArgumentException("Invalid host_group specified: " + name +
          ".  All request host groups must have a corresponding host group in the specified blueprint");
      }

      Collection hosts = (Collection) hostGroupProperties.get("hosts");
      if (hosts == null || hosts.isEmpty()) {
        throw new IllegalArgumentException("Host group '" + name + "' must contain a 'hosts' element");
      }
      for (Object oHost : hosts) {
        Map<String, String> mapHostProperties = (Map<String, String>) oHost;
        //add host information to host group
        String fqdn = mapHostProperties.get("fqdn");
        if (fqdn == null || fqdn.isEmpty()) {
          throw new IllegalArgumentException("Host group '" + name + "' hosts element must include at least one fqdn");
        }
        hostGroup.addHostInfo(fqdn);
      }
      Map<String, Map<String, String>> existingConfigurations = hostGroup.getConfigurationProperties();
      overrideExistingProperties(existingConfigurations, (Collection<Map<String, String>>)
          hostGroupProperties.get("configurations"));

    }
    validateHostMappings(blueprintHostGroups);
  }

  /**
   * Create the cluster resource.
   *
   * @param properties  cluster resource request properties
   *
   * @throws ResourceAlreadyExistsException  cluster resource already exists
   * @throws SystemException                 an unexpected exception occurred
   * @throws NoSuchParentResourceException   shouldn't be thrown as a cluster doesn't have a parent resource
   */
  private void createClusterResource(final Map<String, Object> properties)
      throws ResourceAlreadyExistsException, SystemException, NoSuchParentResourceException {

    createResources(new Command<Void>() {
      @Override
      public Void invoke() throws AmbariException {
        getManagementController().createCluster(getRequest(properties));
        return null;
      }
    });
  }

  /**
   * Persist cluster state for the ambari UI.  Setting this state informs that UI that a cluster has been
   * installed and started and that the monitoring screen for the cluster should be displayed to the user.
   *
   * @param clusterName  name of cluster
   *
   * @throws SystemException if unable to update the cluster with the UI installed flag
   */
  private void persistInstallStateForUI(String clusterName) throws SystemException {
    Map<String, Object> clusterProperties = new HashMap<String, Object>();
    clusterProperties.put(CLUSTER_PROVISIONING_STATE_PROPERTY_ID, "INSTALLED");
    clusterProperties.put(CLUSTER_NAME_PROPERTY_ID, clusterName);

    try {
      getManagementController().updateClusters(
          Collections.singleton(getRequest(clusterProperties)), null);
    } catch (AmbariException e) {
      throw new SystemException("Unable to finalize state of cluster for UI.");
    }
  }

  /**
   * Process cluster configurations.  This includes obtaining the default configuration properties
   * from the stack,overlaying configuration properties specified in the blueprint and cluster
   * create request and updating properties with topology specific information.
   *
   * @param stack                associated stack
   * @param blueprintHostGroups  host groups contained in the blueprint
   */
  private void processConfigurations(Map<String, Map<String, String>> blueprintConfigurations,
                                     Map<String, Map<String, Map<String, String>>> blueprintAttributes,
                                     Stack stack, Map<String, HostGroupImpl> blueprintHostGroups)  {


    for (String service : getServicesToDeploy(stack, blueprintHostGroups)) {
      for (String type : stack.getConfigurationTypes(service)) {
        Map<String, String> typeProps = mapClusterConfigurations.get(type);
        if (typeProps == null) {
          typeProps = new HashMap<String, String>();
          mapClusterConfigurations.put(type, typeProps);
        }
        typeProps.putAll(stack.getConfigurationProperties(service, type));
        Map<String, Map<String, String>> stackTypeAttributes = stack.getConfigurationAttributes(service, type);
        if (!stackTypeAttributes.isEmpty()) {
          if (!mapClusterAttributes.containsKey(type)) {
            mapClusterAttributes.put(type, new HashMap<String, Map<String, String>>());
          }
          Map<String, Map<String, String>> typeAttrs = mapClusterAttributes.get(type);
          for (Map.Entry<String, Map<String, String>> attribute : stackTypeAttributes.entrySet()) {
            String attributeName = attribute.getKey();
            Map<String, String> attributes = typeAttrs.get(attributeName);
            if (attributes == null) {
                attributes = new HashMap<String, String>();
                typeAttrs.put(attributeName, attributes);
            }
            attributes.putAll(attribute.getValue());
          }
        }
      }
    }
    processBlueprintClusterConfigurations(blueprintConfigurations);
    processBlueprintClusterConfigAttributes(blueprintAttributes);

    BlueprintConfigurationProcessor configurationProcessor = new BlueprintConfigurationProcessor(mapClusterConfigurations);
    configurationProcessor.doUpdateForClusterCreate(blueprintHostGroups, stack);
    setMissingConfigurations(blueprintHostGroups);
  }

  /**
   * Since global configs are deprecated since 1.7.0, but still supported.
   * We should automatically map any globals used, to *-env dictionaries.
   *
   * @param blueprintConfigurations  map of blueprint configurations keyed by type
   */
  private void handleGlobalsBackwardsCompability(Stack stack,
      Map<String, Map<String, String>> blueprintConfigurations, String clusterName) {
    StackId stackId = new StackId(stack.getName(), stack.getVersion());
    configHelper.moveDeprecatedGlobals(stackId, blueprintConfigurations, clusterName);
  }

  /**
   * Process cluster scoped configurations provided in blueprint.
   *
   * @param blueprintConfigurations  map of blueprint configurations keyed by type
   */
  private void processBlueprintClusterConfigurations(Map<String, Map<String, String>> blueprintConfigurations) {
    for (Map.Entry<String, Map<String, String>> entry : blueprintConfigurations.entrySet()) {
      Map<String, String> properties = entry.getValue();
      if (properties != null && !properties.isEmpty()) {
        String type = entry.getKey();
        Map<String, String> typeProps = mapClusterConfigurations.get(type);
        if (typeProps == null) {
          typeProps = new HashMap<String, String>();
          mapClusterConfigurations.put(type, typeProps);
        }
        // override default properties
        typeProps.putAll(properties);
      }
    }
  }

  /**
   * Process cluster scoped configuration attributes provided in blueprint.
   *
   * @param blueprintAttributes  map of configuration type to configuration attributes and their values
   */
  private void processBlueprintClusterConfigAttributes(Map<String, Map<String, Map<String, String>>> blueprintAttributes) {
    for (Map.Entry<String, Map<String, Map<String, String>>> entry : blueprintAttributes.entrySet()) {
      Map<String, Map<String, String>> attributes = entry.getValue();
      if (attributes != null && !attributes.isEmpty()) {
        String type = entry.getKey();
        if (!mapClusterAttributes.containsKey(type)) {
          mapClusterAttributes.put(type, new HashMap<String, Map<String, String>>());
        }
        Map<String, Map<String, String>> typeAttrs = mapClusterAttributes.get(type);
        for (Map.Entry<String, Map<String, String>> attribute : attributes.entrySet()) {
          String attributeName = attribute.getKey();
          if (!typeAttrs.containsKey(attributeName)) {
            typeAttrs.put(attributeName, new HashMap<String, String>());
          }
          typeAttrs.get(attributeName).putAll(attribute.getValue());
        }
      }
    }
  }

  /**
   * Explicitly set any properties that are required but not currently provided in the stack definition.
   */
  void setMissingConfigurations(Map<String, HostGroupImpl> blueprintHostGroups) {
    // AMBARI-5206
    final Map<String , String> userProps = new HashMap<String , String>();

    // only add user properties to the map for
    // services actually included in the blueprint definition
    if (isServiceIncluded("OOZIE", blueprintHostGroups)) {
      userProps.put("oozie_user", "oozie-env");
    }

    if (isServiceIncluded("HIVE", blueprintHostGroups)) {
      userProps.put("hive_user", "hive-env");
      userProps.put("hcat_user", "hive-env");
    }

    if (isServiceIncluded("HBASE", blueprintHostGroups)) {
      userProps.put("hbase_user", "hbase-env");
    }

    if (isServiceIncluded("FALCON", blueprintHostGroups)) {
      userProps.put("falcon_user", "falcon-env");
    }


    String proxyUserHosts  = "hadoop.proxyuser.%s.hosts";
    String proxyUserGroups = "hadoop.proxyuser.%s.groups";

    for (String property : userProps.keySet()) {
      String configType = userProps.get(property);
      Map<String, String> configs = mapClusterConfigurations.get(configType);
      if (configs != null) {
        String user = configs.get(property);
        if (user != null && !user.isEmpty()) {
          ensureProperty("core-site", String.format(proxyUserHosts, user), "*");
          ensureProperty("core-site", String.format(proxyUserGroups, user), "users");
        }
      } else {
        LOG.debug("setMissingConfigurations: no user configuration found for type = " + configType + ".  This may be caused by an error in the blueprint configuration.");
      }

    }
  }


  /**
   * Determines if any components in the specified service are
   *   included in the current blueprint's host group definitions.
   *
   * @param serviceName the Hadoop service name to query on
   * @param blueprintHostGroups the map of Host Groups in the current blueprint
   * @return true if the named service is included in the blueprint
   *         false if the named service it not included in the blueprint
   */
  protected boolean isServiceIncluded(String serviceName, Map<String, HostGroupImpl> blueprintHostGroups) {
    for (String hostGroupName : blueprintHostGroups.keySet()) {
      HostGroupImpl hostGroup = blueprintHostGroups.get(hostGroupName);
      if (hostGroup.getServices().contains(serviceName)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Ensure that the specified property exists.
   * If not, set a default value.
   *
   * @param type          config type
   * @param property      property name
   * @param defaultValue  default value
   */
  private void ensureProperty(String type, String property, String defaultValue) {
    Map<String, String> properties = mapClusterConfigurations.get(type);
    if (properties == null) {
      properties = new HashMap<String, String>();
      mapClusterConfigurations.put(type, properties);
    }

    if (! properties.containsKey(property)) {
      properties.put(property, defaultValue);
    }
  }

  /**
   * Get set of services which are to be deployed.
   *
   * @param stack                stack information
   * @param blueprintHostGroups  host groups contained in blueprint
   *
   * @return set of service names which will be deployed
   */
  private Set<String> getServicesToDeploy(Stack stack, Map<String, HostGroupImpl> blueprintHostGroups) {
    Set<String> services = new HashSet<String>();
    for (HostGroupImpl group : blueprintHostGroups.values()) {
      if (! group.getHostInfo().isEmpty()) {
        services.addAll(stack.getServicesForComponents(group.getComponents()));
      }
    }
    //remove entry associated with Ambari Server since this isn't recognized by Ambari
    services.remove(null);

    return services;
  }

  /**
   * Register config groups for host group scoped configuration.
   * For each host group with configuration specified in the blueprint, a config group is created
   * and the hosts associated with the host group are assigned to the config group.
   *
   * @param clusterName  name of cluster
   * @param hostGroups   map of host group name to host group
   * @param stack        associated stack information
   *
   * @throws ResourceAlreadyExistsException attempt to create a config group that already exists
   * @throws SystemException                an unexpected exception occurs
   * @throws UnsupportedPropertyException   an invalid property is provided when creating a config group
   * @throws NoSuchParentResourceException  attempt to create a config group for a non-existing cluster
   */
  private void registerConfigGroups(String clusterName, Map<String, HostGroupImpl> hostGroups, Stack stack) throws
      ResourceAlreadyExistsException, SystemException,
      UnsupportedPropertyException, NoSuchParentResourceException {

    for (HostGroupImpl group : hostGroups.values()) {
      HostGroupEntity entity = group.getEntity();
      Map<String, Map<String, Config>> groupConfigs = new HashMap<String, Map<String, Config>>();
      
      handleGlobalsBackwardsCompability(stack, group.getConfigurationProperties(), clusterName);
      for (Map.Entry<String, Map<String, String>> entry: group.getConfigurationProperties().entrySet()) {
        String type = entry.getKey();
        String service = stack.getServiceForConfigType(type);
        Config config = new ConfigImpl(type);
        config.setTag(entity.getName());
        config.setProperties(entry.getValue());
        Map<String, Config> serviceConfigs = groupConfigs.get(service);
        if (serviceConfigs == null) {
          serviceConfigs = new HashMap<String, Config>();
          groupConfigs.put(service, serviceConfigs);
        }
        serviceConfigs.put(type, config);
      }

      for (Map.Entry<String, Map<String, Config>> entry : groupConfigs.entrySet()) {
        String service = entry.getKey();
        Map<String, Config> serviceConfigs = entry.getValue();
        String hostGroupName = getConfigurationGroupName(entity.getBlueprintName(), entity.getName());
        ConfigGroupRequest request = new ConfigGroupRequest(
            null, clusterName, hostGroupName, service, "Host Group Configuration",
            new HashSet<String>(group.getHostInfo()), serviceConfigs);

        ((ConfigGroupResourceProvider) getResourceProvider(Resource.Type.ConfigGroup)).
            createResources(Collections.singleton(request));
      }
    }
  }

  /**
   * Validate that a host is only mapped to a single host group.
   *
   * @param hostGroups map of host group name to host group
   */
  private void validateHostMappings(Map<String, HostGroupImpl> hostGroups) {
    Collection<String> mappedHosts = new HashSet<String>();
    Collection<String> flaggedHosts = new HashSet<String>();

    for (HostGroupImpl hostgroup : hostGroups.values()) {
      for (String host : hostgroup.getHostInfo()) {
        if (mappedHosts.contains(host)) {
          flaggedHosts.add(host);
        } else {
          mappedHosts.add(host);
        }
      }
    }

    if (! flaggedHosts.isEmpty())  {
      throw new IllegalArgumentException("A host may only be mapped to a single host group at this time." +
                                         "  The following hosts are mapped to more than one host group: " +
                                         flaggedHosts);
    }
  }

  /**
   * Determine whether or not the cluster resource identified
   * by the given cluster name should be included based on the
   * permissions granted to the current user.
   *
   * @param clusterName  the cluster name
   * @param readOnly     indicate whether or not this is for a read only operation
   *
   * @return true if the cluster should be included based on the permissions of the current user
   */
  private boolean includeCluster(String clusterName, boolean readOnly) {
    return getManagementController().getClusters().checkPermission(clusterName, readOnly);
  }


  /**
   * Internal class meant to represent the collection of configuration
   * items and configuration attributes that are associated with a given service.
   *
   * This class is used to support proper configuration versioning when
   * Ambari Blueprints is used to deploy a cluster.
   */
  private static class BlueprintServiceConfigRequest {

    private final String serviceName;

    private List<BlueprintServiceConfigElement> configElements =
      new LinkedList<BlueprintServiceConfigElement>();

    BlueprintServiceConfigRequest(String serviceName) {
      this.serviceName = serviceName;
    }

    void addConfigElement(String typeName, Map<String, String> configuration, Map<String, Map<String, String>> attributes) {
      configElements.add(new BlueprintServiceConfigElement(typeName, configuration, attributes));
    }

    public String getServiceName() {
      return serviceName;
    }

    List<BlueprintServiceConfigElement> getConfigElements() {
      return configElements;
    }

  }

  /**
   * Internal class that represents the configuration
   *  and attributes for a given configuration type.
   */
  private static class BlueprintServiceConfigElement {
    private final String typeName;

    private final Map<String, String> configuration;

    private final Map<String, Map<String, String>> attributes;

    BlueprintServiceConfigElement(String typeName, Map<String, String> configuration, Map<String, Map<String, String>> attributes) {
      this.typeName = typeName;
      this.configuration = configuration;
      this.attributes = attributes;
    }

    public String getTypeName() {
      return typeName;
    }

    public Map<String, String> getConfiguration() {
      return configuration;
    }

    public Map<String, Map<String, String>> getAttributes() {
      return attributes;
    }

  }

}

