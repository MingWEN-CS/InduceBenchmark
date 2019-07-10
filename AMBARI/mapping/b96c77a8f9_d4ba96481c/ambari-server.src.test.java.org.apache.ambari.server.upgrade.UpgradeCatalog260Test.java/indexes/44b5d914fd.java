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

package org.apache.ambari.server.upgrade;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;

import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.KerberosHelper;
import org.apache.ambari.server.controller.MaintenanceStateHelper;
import org.apache.ambari.server.orm.DBAccessor;
import org.apache.ambari.server.orm.DBAccessor.DBColumnInfo;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.stack.OsFamily;
import org.easymock.Capture;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.easymock.MockType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.gson.Gson;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;

/**
 * {@link UpgradeCatalog260} unit tests.
 */
@RunWith(EasyMockRunner.class)
public class UpgradeCatalog260Test {

  //  private Injector injector;
  @Mock(type = MockType.STRICT)
  private Provider<EntityManager> entityManagerProvider;

  @Mock(type = MockType.NICE)
  private EntityManager entityManager;

  @Mock(type = MockType.NICE)
  private DBAccessor dbAccessor;

  @Mock(type = MockType.NICE)
  private Configuration configuration;

  @Mock(type = MockType.NICE)
  private Connection connection;

  @Mock(type = MockType.NICE)
  private Statement statement;

  @Mock(type = MockType.NICE)
  private ResultSet resultSet;

  @Mock(type = MockType.NICE)
  private OsFamily osFamily;

  @Mock(type = MockType.NICE)
  private KerberosHelper kerberosHelper;

  @Mock(type = MockType.NICE)
  private ActionManager actionManager;

  @Mock(type = MockType.NICE)
  private Config config;

  @Mock(type = MockType.STRICT)
  private Service service;

  @Mock(type = MockType.NICE)
  private Clusters clusters;

  @Mock(type = MockType.NICE)
  private Cluster cluster;

  @Mock(type = MockType.NICE)
  private Injector injector;

  @Before
  public void init() {
    reset(entityManagerProvider, injector);

    expect(entityManagerProvider.get()).andReturn(entityManager).anyTimes();

    expect(injector.getInstance(Gson.class)).andReturn(null).anyTimes();
    expect(injector.getInstance(MaintenanceStateHelper.class)).andReturn(null).anyTimes();
    expect(injector.getInstance(KerberosHelper.class)).andReturn(kerberosHelper).anyTimes();

    replay(entityManagerProvider, injector);
  }

  @After
  public void tearDown() {
  }

  @Test
  public void testExecuteDDLUpdates() throws Exception {

    List<Integer> current = new ArrayList<Integer>();
    current.add(1);

    expect(dbAccessor.getConnection()).andReturn(connection).anyTimes();
    expect(connection.createStatement()).andReturn(statement).anyTimes();
    expect(statement.executeQuery(anyObject(String.class))).andReturn(resultSet).anyTimes();
    expect(configuration.getDatabaseType()).andReturn(Configuration.DatabaseType.POSTGRES).anyTimes();


    Capture<String[]> scdcaptureKey = newCapture();
    Capture<String[]> scdcaptureValue = newCapture();
    expectGetCurrentVersionID(current, scdcaptureKey, scdcaptureValue);

    Capture<DBColumnInfo> scdstadd1 = newCapture();
    Capture<DBColumnInfo> scdstalter1 = newCapture();
    Capture<DBColumnInfo> scdstadd2 = newCapture();
    Capture<DBColumnInfo> scdstalter2 = newCapture();
    expectUpdateServiceComponentDesiredStateTable(scdstadd1, scdstalter1, scdstadd2, scdstalter2);

    Capture<DBColumnInfo> sdstadd = newCapture();
    Capture<DBColumnInfo> sdstalter = newCapture();
    expectUpdateServiceDesiredStateTable(sdstadd, sdstalter);

    Capture<DBColumnInfo> selectedColumnInfo = newCapture();
    Capture<DBColumnInfo> selectedmappingColumnInfo = newCapture();
    Capture<DBColumnInfo> selectedTimestampColumnInfo = newCapture();
    Capture<DBColumnInfo> createTimestampColumnInfo = newCapture();
    expectAddSelectedCollumsToClusterconfigTable(selectedColumnInfo, selectedmappingColumnInfo, selectedTimestampColumnInfo, createTimestampColumnInfo);

    expectUpdateHostComponentDesiredStateTable();
    expectUpdateHostComponentStateTable();

    Capture<DBColumnInfo> rvid = newCapture();
    Capture<DBColumnInfo> orchestration = newCapture();
    expectUpdateUpgradeTable(rvid, orchestration);

    Capture<List<DBAccessor.DBColumnInfo>> columns = newCapture();
    expectCreateUpgradeHistoryTable(columns);

    expectDropStaleTables();

    replay(dbAccessor, configuration, connection, statement, resultSet);

    Module module = new Module() {
      @Override
      public void configure(Binder binder) {
        binder.bind(DBAccessor.class).toInstance(dbAccessor);
        binder.bind(OsFamily.class).toInstance(osFamily);
        binder.bind(EntityManager.class).toInstance(entityManager);
        binder.bind(Configuration.class).toInstance(configuration);
      }
    };

    Injector injector = Guice.createInjector(module);
    UpgradeCatalog260 upgradeCatalog260 = injector.getInstance(UpgradeCatalog260.class);
    upgradeCatalog260.executeDDLUpdates();

    verify(dbAccessor);

    verifyGetCurrentVersionID(scdcaptureKey, scdcaptureValue);
    verifyUpdateServiceComponentDesiredStateTable(scdstadd1, scdstalter1, scdstadd2, scdstalter2);
    verifyUpdateServiceDesiredStateTable(sdstadd, sdstalter);
    verifyAddSelectedCollumsToClusterconfigTable(selectedColumnInfo, selectedmappingColumnInfo, selectedTimestampColumnInfo, createTimestampColumnInfo);
    verifyUpdateUpgradeTable(rvid, orchestration);
    verifyCreateUpgradeHistoryTable(columns);

  }

  public void expectDropStaleTables() throws SQLException {
    dbAccessor.dropTable(eq(UpgradeCatalog260.CLUSTER_CONFIG_MAPPING_TABLE));
    expectLastCall().once();
    dbAccessor.dropTable(eq(UpgradeCatalog260.CLUSTER_VERSION_TABLE));
    expectLastCall().once();
    dbAccessor.dropTable(eq(UpgradeCatalog260.SERVICE_COMPONENT_HISTORY_TABLE));
    expectLastCall().once();
  }

  public void verifyCreateUpgradeHistoryTable(Capture<List<DBColumnInfo>> columns) {
    List<DBColumnInfo> columnsValue = columns.getValue();
    Assert.assertEquals(columnsValue.size(), 6);

    DBColumnInfo id = columnsValue.get(0);
    Assert.assertEquals(UpgradeCatalog260.ID_COLUMN, id.getName());
    Assert.assertEquals(Long.class, id.getType());
    Assert.assertEquals(null, id.getLength());
    Assert.assertEquals(null, id.getDefaultValue());
    Assert.assertEquals(false, id.isNullable());

    DBColumnInfo upgradeId = columnsValue.get(1);
    Assert.assertEquals(UpgradeCatalog260.UPGRADE_ID_COLUMN, upgradeId.getName());
    Assert.assertEquals(Long.class, upgradeId.getType());
    Assert.assertEquals(null, upgradeId.getLength());
    Assert.assertEquals(null, upgradeId.getDefaultValue());
    Assert.assertEquals(false, upgradeId.isNullable());

    DBColumnInfo serviceName = columnsValue.get(2);
    Assert.assertEquals(UpgradeCatalog260.SERVICE_NAME_COLUMN, serviceName.getName());
    Assert.assertEquals(String.class, serviceName.getType());
    Assert.assertEquals(Integer.valueOf(255), serviceName.getLength());
    Assert.assertEquals(null, serviceName.getDefaultValue());
    Assert.assertEquals(false, serviceName.isNullable());

    DBColumnInfo componentName = columnsValue.get(3);
    Assert.assertEquals(UpgradeCatalog260.COMPONENT_NAME_COLUMN, componentName.getName());
    Assert.assertEquals(String.class, componentName.getType());
    Assert.assertEquals(Integer.valueOf(255), componentName.getLength());
    Assert.assertEquals(null, componentName.getDefaultValue());
    Assert.assertEquals(false, componentName.isNullable());

    DBColumnInfo fromRepoID = columnsValue.get(4);
    Assert.assertEquals(UpgradeCatalog260.FROM_REPO_VERSION_ID_COLUMN, fromRepoID.getName());
    Assert.assertEquals(Long.class, fromRepoID.getType());
    Assert.assertEquals(null, fromRepoID.getLength());
    Assert.assertEquals(null, fromRepoID.getDefaultValue());
    Assert.assertEquals(false, fromRepoID.isNullable());

    DBColumnInfo targetRepoID = columnsValue.get(5);
    Assert.assertEquals(UpgradeCatalog260.TARGET_REPO_VERSION_ID_COLUMN, targetRepoID.getName());
    Assert.assertEquals(Long.class, targetRepoID.getType());
    Assert.assertEquals(null, targetRepoID.getLength());
    Assert.assertEquals(null, targetRepoID.getDefaultValue());
    Assert.assertEquals(false, targetRepoID.isNullable());
  }

  public void expectCreateUpgradeHistoryTable(Capture<List<DBColumnInfo>> columns) throws SQLException {
    dbAccessor.createTable(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), capture(columns));
    expectLastCall().once();

    dbAccessor.addPKConstraint(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), eq(UpgradeCatalog260.PK_UPGRADE_HIST), eq(UpgradeCatalog260.ID_COLUMN));
    expectLastCall().once();

    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_HIST_UPGRADE_ID), eq(UpgradeCatalog260.UPGRADE_ID_COLUMN), eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.UPGRADE_ID_COLUMN), eq(false));
    expectLastCall().once();
    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_HIST_FROM_REPO), eq(UpgradeCatalog260.FROM_REPO_VERSION_ID_COLUMN), eq(UpgradeCatalog260.REPO_VERSION_TABLE), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(false));
    expectLastCall().once();
    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_HIST_TARGET_REPO), eq(UpgradeCatalog260.TARGET_REPO_VERSION_ID_COLUMN), eq(UpgradeCatalog260.REPO_VERSION_TABLE), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(false));
    expectLastCall().once();
    dbAccessor.addUniqueConstraint(eq(UpgradeCatalog260.UPGRADE_HISTORY_TABLE), eq(UpgradeCatalog260.UQ_UPGRADE_HIST), eq(UpgradeCatalog260.UPGRADE_ID_COLUMN), eq(UpgradeCatalog260.COMPONENT_NAME_COLUMN), eq(UpgradeCatalog260.SERVICE_NAME_COLUMN));
    expectLastCall().once();
  }

  public void verifyUpdateUpgradeTable(Capture<DBColumnInfo> rvid, Capture<DBColumnInfo> orchestration) {
    DBColumnInfo rvidValue = rvid.getValue();
    Assert.assertEquals(UpgradeCatalog260.REPO_VERSION_ID_COLUMN, rvidValue.getName());
    Assert.assertEquals(Long.class, rvidValue.getType());
    Assert.assertEquals(null, rvidValue.getLength());
    Assert.assertEquals(null, rvidValue.getDefaultValue());
    Assert.assertEquals(false, rvidValue.isNullable());

    DBColumnInfo orchestrationValue = orchestration.getValue();
    Assert.assertEquals(UpgradeCatalog260.ORCHESTRATION_COLUMN, orchestrationValue.getName());
    Assert.assertEquals(String.class, orchestrationValue.getType());
    Assert.assertEquals(Integer.valueOf(255), orchestrationValue.getLength());
    Assert.assertEquals(UpgradeCatalog260.STANDARD, orchestrationValue.getDefaultValue());
    Assert.assertEquals(false, orchestrationValue.isNullable());
  }

  public void expectUpdateUpgradeTable(Capture<DBColumnInfo> rvid, Capture<DBColumnInfo> orchestration) throws SQLException {
    dbAccessor.clearTable(eq(UpgradeCatalog260.UPGRADE_TABLE));
    expectLastCall().once();
    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_FROM_REPO_ID));
    expectLastCall().once();
    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_TO_REPO_ID));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.FROM_REPO_VERSION_ID_COLUMN));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.TO_REPO_VERSION_ID_COLUMN));
    expectLastCall().once();

    dbAccessor.addColumn(eq(UpgradeCatalog260.UPGRADE_TABLE), capture(rvid));
    expectLastCall().once();
    dbAccessor.addColumn(eq(UpgradeCatalog260.UPGRADE_TABLE), capture(orchestration));
    expectLastCall().once();

    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.UPGRADE_TABLE), eq(UpgradeCatalog260.FK_UPGRADE_REPO_VERSION_ID), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(UpgradeCatalog260.REPO_VERSION_TABLE), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(false));
    expectLastCall().once();
  }

  public void expectUpdateHostComponentStateTable() throws SQLException {
    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.HOST_COMPONENT_STATE_TABLE), eq(UpgradeCatalog260.FK_HCS_CURRENT_STACK_ID));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.HOST_COMPONENT_STATE_TABLE), eq(UpgradeCatalog260.CURRENT_STACK_ID_COLUMN));
    expectLastCall().once();
  }

  public void expectUpdateHostComponentDesiredStateTable() throws SQLException {
    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.HOST_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.FK_HCDS_DESIRED_STACK_ID));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.HOST_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.DESIRED_STACK_ID_COLUMN));
    expectLastCall().once();
  }

  public void verifyAddSelectedCollumsToClusterconfigTable(Capture<DBColumnInfo> selectedColumnInfo, Capture<DBColumnInfo> selectedmappingColumnInfo, Capture<DBColumnInfo> selectedTimestampColumnInfo, Capture<DBColumnInfo> createTimestampColumnInfo) {
    DBColumnInfo selectedColumnInfoValue = selectedColumnInfo.getValue();
    Assert.assertEquals(UpgradeCatalog260.SELECTED_COLUMN, selectedColumnInfoValue.getName());
    Assert.assertEquals(Short.class, selectedColumnInfoValue.getType());
    Assert.assertEquals(null, selectedColumnInfoValue.getLength());
    Assert.assertEquals(0, selectedColumnInfoValue.getDefaultValue());
    Assert.assertEquals(false, selectedColumnInfoValue.isNullable());

    DBColumnInfo selectedmappingColumnInfoValue = selectedmappingColumnInfo.getValue();
    Assert.assertEquals(UpgradeCatalog260.SELECTED_COLUMN, selectedmappingColumnInfoValue.getName());
    Assert.assertEquals(Integer.class, selectedmappingColumnInfoValue.getType());
    Assert.assertEquals(null, selectedmappingColumnInfoValue.getLength());
    Assert.assertEquals(0, selectedmappingColumnInfoValue.getDefaultValue());
    Assert.assertEquals(false, selectedmappingColumnInfoValue.isNullable());

    DBColumnInfo selectedTimestampColumnInfoValue = selectedTimestampColumnInfo.getValue();
    Assert.assertEquals(UpgradeCatalog260.SELECTED_TIMESTAMP_COLUMN, selectedTimestampColumnInfoValue.getName());
    Assert.assertEquals(Long.class, selectedTimestampColumnInfoValue.getType());
    Assert.assertEquals(null, selectedTimestampColumnInfoValue.getLength());
    Assert.assertEquals(0, selectedTimestampColumnInfoValue.getDefaultValue());
    Assert.assertEquals(false, selectedTimestampColumnInfoValue.isNullable());

    DBColumnInfo createTimestampColumnInfoValue = createTimestampColumnInfo.getValue();
    Assert.assertEquals(UpgradeCatalog260.CREATE_TIMESTAMP_COLUMN, createTimestampColumnInfoValue.getName());
    Assert.assertEquals(Long.class, createTimestampColumnInfoValue.getType());
    Assert.assertEquals(null, createTimestampColumnInfoValue.getLength());
    Assert.assertEquals(null, createTimestampColumnInfoValue.getDefaultValue());
    Assert.assertEquals(false, createTimestampColumnInfoValue.isNullable());
  }

  public void expectAddSelectedCollumsToClusterconfigTable(Capture<DBColumnInfo> selectedColumnInfo, Capture<DBColumnInfo> selectedmappingColumnInfo, Capture<DBColumnInfo> selectedTimestampColumnInfo, Capture<DBColumnInfo> createTimestampColumnInfo) throws SQLException {
    dbAccessor.copyColumnToAnotherTable(eq(UpgradeCatalog260.CLUSTER_CONFIG_MAPPING_TABLE), capture(selectedmappingColumnInfo),
        eq(UpgradeCatalog260.CLUSTER_ID_COLUMN), eq(UpgradeCatalog260.TYPE_NAME_COLUMN), eq(UpgradeCatalog260.VERSION_TAG_COLUMN), eq(UpgradeCatalog260.CLUSTER_CONFIG_TABLE), capture(selectedColumnInfo),
        eq(UpgradeCatalog260.CLUSTER_ID_COLUMN), eq(UpgradeCatalog260.TYPE_NAME_COLUMN), eq(UpgradeCatalog260.VERSION_TAG_COLUMN), eq(UpgradeCatalog260.SELECTED_COLUMN), eq(UpgradeCatalog260.SELECTED), eq(0));
    expectLastCall().once();

    dbAccessor.copyColumnToAnotherTable(eq(UpgradeCatalog260.CLUSTER_CONFIG_MAPPING_TABLE), capture(createTimestampColumnInfo),
        eq(UpgradeCatalog260.CLUSTER_ID_COLUMN), eq(UpgradeCatalog260.TYPE_NAME_COLUMN), eq(UpgradeCatalog260.VERSION_TAG_COLUMN), eq(UpgradeCatalog260.CLUSTER_CONFIG_TABLE), capture(selectedTimestampColumnInfo),
        eq(UpgradeCatalog260.CLUSTER_ID_COLUMN), eq(UpgradeCatalog260.TYPE_NAME_COLUMN), eq(UpgradeCatalog260.VERSION_TAG_COLUMN), eq(UpgradeCatalog260.SELECTED_COLUMN), eq(UpgradeCatalog260.SELECTED), eq(0));
    expectLastCall().once();
  }

  public void verifyUpdateServiceDesiredStateTable(Capture<DBColumnInfo> sdstadd, Capture<DBColumnInfo> sdstalter) {
    DBColumnInfo sdstaddValue = sdstadd.getValue();
    Assert.assertEquals(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN, sdstaddValue.getName());
    Assert.assertEquals(1, sdstaddValue.getDefaultValue());
    Assert.assertEquals(Long.class, sdstaddValue.getType());
    Assert.assertEquals(false, sdstaddValue.isNullable());
    Assert.assertEquals(null, sdstaddValue.getLength());

    DBColumnInfo sdstalterValue = sdstalter.getValue();
    Assert.assertEquals(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN, sdstalterValue.getName());
    Assert.assertEquals(null, sdstalterValue.getDefaultValue());
    Assert.assertEquals(Long.class, sdstalterValue.getType());
    Assert.assertEquals(false, sdstalterValue.isNullable());
    Assert.assertEquals(null, sdstalterValue.getLength());
  }

  public void expectUpdateServiceDesiredStateTable(Capture<DBColumnInfo> sdstadd, Capture<DBColumnInfo> sdstalter) throws SQLException {
    dbAccessor.addColumn(eq(UpgradeCatalog260.SERVICE_DESIRED_STATE_TABLE), capture(sdstadd));
    expectLastCall().once();
    dbAccessor.alterColumn(eq(UpgradeCatalog260.SERVICE_DESIRED_STATE_TABLE), capture(sdstalter));
    expectLastCall().once();

    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.SERVICE_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.FK_REPO_VERSION_ID), eq(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN), eq(UpgradeCatalog260.REPO_VERSION_TABLE), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(false));
    expectLastCall().once();
    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.SERVICE_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.FK_SDS_DESIRED_STACK_ID));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.SERVICE_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.DESIRED_STACK_ID_COLUMN));
    expectLastCall().once();
  }

  public void verifyUpdateServiceComponentDesiredStateTable(Capture<DBColumnInfo> scdstadd1, Capture<DBColumnInfo> scdstalter1, Capture<DBColumnInfo> scdstadd2, Capture<DBColumnInfo> scdstalter2) {
    DBColumnInfo scdstaddValue1 = scdstadd1.getValue();
    Assert.assertEquals(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN, scdstaddValue1.getName());
    Assert.assertEquals(1, scdstaddValue1.getDefaultValue());
    Assert.assertEquals(Long.class, scdstaddValue1.getType());
    Assert.assertEquals(false, scdstaddValue1.isNullable());

    DBColumnInfo scdstalterValue1 = scdstalter1.getValue();
    Assert.assertEquals(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN, scdstalterValue1.getName());
    Assert.assertEquals(null, scdstalterValue1.getDefaultValue());
    Assert.assertEquals(Long.class, scdstalterValue1.getType());
    Assert.assertEquals(false, scdstalterValue1.isNullable());

    DBColumnInfo scdstaddValue2 = scdstadd2.getValue();
    Assert.assertEquals(UpgradeCatalog260.REPO_STATE_COLUMN, scdstaddValue2.getName());
    Assert.assertEquals(UpgradeCatalog260.CURRENT, scdstaddValue2.getDefaultValue());
    Assert.assertEquals(String.class, scdstaddValue2.getType());
    Assert.assertEquals(false, scdstaddValue2.isNullable());
    Assert.assertEquals(Integer.valueOf(255), scdstaddValue2.getLength());

    DBColumnInfo scdstalterValue2 = scdstalter2.getValue();
    Assert.assertEquals(UpgradeCatalog260.REPO_STATE_COLUMN, scdstalterValue2.getName());
    Assert.assertEquals(UpgradeCatalog260.NOT_REQUIRED, scdstalterValue2.getDefaultValue());
    Assert.assertEquals(String.class, scdstalterValue2.getType());
    Assert.assertEquals(false, scdstalterValue2.isNullable());
    Assert.assertEquals(Integer.valueOf(255), scdstaddValue2.getLength());
  }

  public void verifyGetCurrentVersionID(Capture<String[]> scdcaptureKey, Capture<String[]> scdcaptureValue) {
    Assert.assertTrue(Arrays.equals(scdcaptureKey.getValue(), new String[]{UpgradeCatalog260.STATE_COLUMN}));
    Assert.assertTrue(Arrays.equals(scdcaptureValue.getValue(), new String[]{UpgradeCatalog260.CURRENT}));
  }

  public void expectUpdateServiceComponentDesiredStateTable(Capture<DBColumnInfo> scdstadd1, Capture<DBColumnInfo> scdstalter1, Capture<DBColumnInfo> scdstadd2, Capture<DBColumnInfo> scdstalter2) throws SQLException {
    dbAccessor.addColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), capture(scdstadd1));
    expectLastCall().once();

    dbAccessor.alterColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), capture(scdstalter1));
    expectLastCall().once();

    dbAccessor.addColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), capture(scdstadd2));
    expectLastCall().once();

    dbAccessor.alterColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), capture(scdstalter2));
    expectLastCall().once();

    dbAccessor.addFKConstraint(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.FK_SCDS_DESIRED_REPO_ID), eq(UpgradeCatalog260.DESIRED_REPO_VERSION_ID_COLUMN), eq(UpgradeCatalog260.REPO_VERSION_TABLE), eq(UpgradeCatalog260.REPO_VERSION_ID_COLUMN), eq(false));
    expectLastCall().once();

    dbAccessor.dropFKConstraint(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.FK_SCDS_DESIRED_STACK_ID));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.DESIRED_STACK_ID_COLUMN));
    expectLastCall().once();
    dbAccessor.dropColumn(eq(UpgradeCatalog260.SERVICE_COMPONENT_DESIRED_STATE_TABLE), eq(UpgradeCatalog260.DESIRED_VERSION_COLUMN));
    expectLastCall().once();
  }

  public void expectGetCurrentVersionID(List<Integer> current, Capture<String[]> scdcaptureKey, Capture<String[]> scdcaptureValue) throws SQLException {
    expect(dbAccessor.getIntColumnValues(eq("cluster_version"), eq("repo_version_id"),
        capture(scdcaptureKey), capture(scdcaptureValue), eq(false))).andReturn(current).once();
  }


}
