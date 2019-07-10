/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.version;

import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.ChildNodeEntry;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.uuid.UUID;
import org.apache.jackrabbit.spi.commons.name.NameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.version.VersionException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Implements a <code>InternalVersionHistory</code>
 */
class InternalVersionHistoryImpl extends InternalVersionItemImpl
        implements InternalVersionHistory {

    /**
     * default logger
     */
    private static Logger log = LoggerFactory.getLogger(InternalVersionHistory.class);

    /**
     * The last current time that was returned by {@link #getCurrentTime()}.
     */
    private static final Calendar CURRENT_TIME = Calendar.getInstance();

    /**
     * the cache of the version labels
     * key = version label (String)
     * value = version name
     */
    private HashMap labelCache = new HashMap();

    /**
     * the root version of this history
     */
    private InternalVersion rootVersion;

    /**
     * the hashmap of all versions names
     * key = version name
     * value = version id (NodeId)
     */
    private LinkedHashMap/*<Name, NodeId>*/ nameCache = new LinkedHashMap/*<Name, NodeId>*/();

    /**
     * the hashmap of all versions
     * key = version id (NodeId)
     * value = version
     */
    private HashMap/*<NodeId, InternalVersion>*/ versionCache = new HashMap/*<NodeId, InternalVersion>*/();

    /**
     * Temporary version cache, used on a refresh.
     */
    private HashMap/*<NodeId, InternalVersion>*/ tempVersionCache = new HashMap/*<NodeId, InternalVersion>*/();

    /**
     * the node that holds the label nodes
     */
    private NodeStateEx labelNode;

    /**
     * the id of this history
     */
    private NodeId historyId;

    /**
     * the id of the versionable node
     */
    private NodeId versionableId;

    /**
     * Creates a new VersionHistory object for the given node state.
     * @param vMgr version manager
     * @param node version history node state
     * @throws RepositoryException if an error occurs
     */
    public InternalVersionHistoryImpl(AbstractVersionManager vMgr, NodeStateEx node)
            throws RepositoryException {
        super(vMgr, node);
        init();
    }

    /**
     * Initialies the history and loads all internal caches
     *
     * @throws RepositoryException if an error occurs
     */
    private void init() throws RepositoryException {
        nameCache.clear();
        versionCache.clear();
        labelCache.clear();

        // get id
        historyId = node.getNodeId();

        // get versionable id
        versionableId = NodeId.valueOf(node.getPropertyValue(NameConstants.JCR_VERSIONABLEUUID).toString());

        // get label node
        labelNode = node.getNode(NameConstants.JCR_VERSIONLABELS, 1);

        // init label cache
        try {
            PropertyState[] labels = labelNode.getProperties();
            for (int i = 0; i < labels.length; i++) {
                PropertyState pState = labels[i];
                if (pState.getType() == PropertyType.REFERENCE) {
                    Name labelName = pState.getName();
                    UUID ref = pState.getValues()[0].getUUID();
                    NodeId id = new NodeId(ref);
                    if (node.getState().hasChildNodeEntry(id)) {
                        labelCache.put(labelName, node.getState().getChildNodeEntry(id).getName());
                    } else {
                        log.warn("Error while resolving label reference. Version missing: " + ref);
                    }
                }
            }
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }

        // get root version
        rootVersion = createVersionInstance(NameConstants.JCR_ROOTVERSION);

        // get version entries
        ChildNodeEntry[] children = (ChildNodeEntry[])
            node.getState().getChildNodeEntries().toArray();
        for (int i = 0; i < children.length; i++) {
            ChildNodeEntry child = children[i];
            if (child.getName().equals(NameConstants.JCR_VERSIONLABELS)) {
                continue;
            }
            nameCache.put(child.getName(), child.getId());
        }

        // fix legacy
        if (rootVersion.getSuccessors().length == 0) {
            Iterator iter = nameCache.keySet().iterator();
            while (iter.hasNext()) {
                Name versionName = (Name) iter.next();
                InternalVersionImpl v = createVersionInstance(versionName);
                v.legacyResolveSuccessors();
            }
        }
    }

    /**
     * Reload this object and all its dependent version objects.
     * @throws RepositoryException if an error occurs
     */
    void reload() throws RepositoryException {
        tempVersionCache.putAll(versionCache);

        init();

        // invalidate all versions that are not referenced any more
        Iterator iter = tempVersionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersionImpl v = (InternalVersionImpl) iter.next();
            v.invalidate();
        }
        tempVersionCache.clear();
    }

    /**
     * Create a version instance.
     * @param name name of the version
     * @return the new internal version
     * @throws IllegalArgumentException if the version does not exist
     */
    InternalVersionImpl createVersionInstance(Name name) {
        try {
            NodeStateEx nodeStateEx = node.getNode(name, 1);
            InternalVersionImpl v = createVersionInstance(nodeStateEx);
            versionCache.put(v.getId(), v);
            vMgr.versionCreated(v);

            // add labels
            Iterator iter = labelCache.keySet().iterator();
            while (iter.hasNext()) {
                Name labelName = (Name) iter.next();
                Name versionName = (Name) labelCache.get(labelName);
                if (v.getName().equals(versionName)) {
                    v.internalAddLabel(labelName);
                }
            }
            return v;
        } catch (RepositoryException e) {
            throw new IllegalArgumentException("Failed to create version " + name + ".");
        }
    }

    /**
     * Create a version instance. May resurrect versions temporarily swapped
     * out when refreshing this history.
     * @param child child node state
     * @return new version instance
     */
    InternalVersionImpl createVersionInstance(NodeStateEx child) {
        InternalVersionImpl v = (InternalVersionImpl) tempVersionCache.remove(child.getNodeId());
        if (v != null) {
            v.clear();
        } else {
            v = new InternalVersionImpl(this, child, child.getName());
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getId() {
        return historyId;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersionItem getParent() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getRootVersion() {
        return rootVersion;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersion(Name versionName) throws VersionException {
        NodeId versionId = (NodeId) nameCache.get(versionName);
        if (versionId == null) {
            throw new VersionException("Version " + versionName + " does not exist.");
        }

        InternalVersion v = (InternalVersion) versionCache.get(versionId);
        if (v == null) {
            v = createVersionInstance(versionName);
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasVersion(Name versionName) {
        return nameCache.containsKey(versionName);
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersion(NodeId id) {
        InternalVersion v = (InternalVersion) versionCache.get(id);
        if (v == null) {
            Iterator iter = nameCache.keySet().iterator();
            while (iter.hasNext()) {
                Name versionName = (Name) iter.next();
                if (nameCache.get(versionName).equals(id)) {
                    v = createVersionInstance(versionName);
                    break;
                }
            }
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersionByLabel(Name label) {
        Name versionName = (Name) labelCache.get(label);
        if (versionName == null) {
            return null;
        }

        NodeId id = (NodeId) nameCache.get(versionName);
        InternalVersion v = (InternalVersion) versionCache.get(id);
        if (v == null) {
            v = createVersionInstance(versionName);
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public Name[] getVersionNames() {
        return (Name[]) nameCache.keySet().toArray(new Name[nameCache.size()]);
    }
    
    /**
     * {@inheritDoc}
     */
    public int getNumVersions() {
        return nameCache.size();
    }

    /**
     * {@inheritDoc}
     */
    public UUID getVersionableUUID() {
        return versionableId.getUUID();
    }

    /**
     * {@inheritDoc}
     */
    public Name[] getVersionLabels() {
        return (Name[]) labelCache.keySet().toArray(new Name[labelCache.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getVersionLabelsId() {
        return labelNode.getNodeId();
    }

    /**
     * Removes the indicated version from this VersionHistory. If the specified
     * vesion does not exist, if it specifies the root version or if it is
     * referenced by any node e.g. as base version, a VersionException is thrown.
     * <p/>
     * all successors of the removed version become successors of the
     * predecessors of the removed version and vice versa. then, the entire
     * version node and all its subnodes are removed.
     *
     * @param versionName name of the version to remove
     * @throws VersionException if removal is not possible
     */
    void removeVersion(Name versionName) throws RepositoryException {

        InternalVersionImpl v = (InternalVersionImpl) getVersion(versionName);
        if (v.equals(rootVersion)) {
            String msg = "Removal of " + versionName + " not allowed.";
            log.debug(msg);
            throw new VersionException(msg);
        }
        // check if any references (from outside the version storage) exist on this version
        if (vMgr.hasItemReferences(v.getId())) {
            throw new ReferentialIntegrityException("Unable to remove version. At least once referenced.");
        }

        // unregister from labels
        Name[] labels = v.internalGetLabels();
        for (int i = 0; i < labels.length; i++) {
            v.internalRemoveLabel(labels[i]);
            labelNode.removeProperty(labels[i]);
        }
        // detach from the version graph
        v.internalDetach();

        // remove from persistence state
        node.removeNode(v.getName());

        // and remove from history
        versionCache.remove(v.getId());
        nameCache.remove(versionName);
        vMgr.versionDestroyed(v);

        // Check if this was the last version in addition to the root version
        if (!vMgr.hasItemReferences(node.getNodeId())) {
            log.debug("Current version history has no references");
            NodeStateEx[] childNodes = node.getChildNodes();

            // Check if there is only root version and version labels nodes
            if (childNodes.length == 2) {
                log.debug("Removing orphan version history as it contains only two children");
                NodeStateEx parentNode = vMgr.getNodeStateEx(node.getParentId());
                // Remove version history node
                parentNode.removeNode(node.getName());
                // store changes for this node and his children
                parentNode.store();
            }
        } else {
            log.debug("Current version history has at least one reference");
            // store changes
            node.store();
        }

        // now also remove from labelCache
        for (int i = 0; i < labels.length; i++) {
            labelCache.remove(labels[i]);
        }
    }

    /**
     * Sets the version <code>label</code> to the given <code>version</code>.
     * If the label is already assigned to another version, a VersionException is
     * thrown unless <code>move</code> is <code>true</code>. If <code>version</code>
     * is <code>null</code>, the label is removed from the respective version.
     * In either case, the version the label was previously assigned to is returned,
     * or <code>null</code> of the label was not moved.
     *
     * @param versionName the name of the version
     * @param label the label to assgign
     * @param move  flag what to do by collisions
     * @return the version that was previously assigned by this label or <code>null</code>.
     * @throws VersionException if the version does not exist or if the label is already defined.
     */
    InternalVersion setVersionLabel(Name versionName, Name label, boolean move)
            throws VersionException {
        InternalVersion version =
            (versionName != null) ? getVersion(versionName) : null;
        if (versionName != null && version == null) {
            throw new VersionException("Version " + versionName + " does not exist in this version history.");
        }
        Name prevName = (Name) labelCache.get(label);
        InternalVersionImpl prev = null;
        if (prevName == null) {
            if (version == null) {
                return null;
            }
        } else {
            prev = (InternalVersionImpl) getVersion(prevName);
            if (prev.equals(version)) {
                return version;
            } else if (!move) {
                // already defined elsewhere, throw
                throw new VersionException("Version label " + label + " already defined for version " + prev.getName());
            }
        }

        // update persistence
        try {
            if (version == null) {
                labelNode.removeProperty(label);
            } else {
                labelNode.setPropertyValue(label, InternalValue.create(version.getId().getUUID()));
            }
            labelNode.store();
        } catch (RepositoryException e) {
            throw new VersionException(e);
        }

        // update internal structures
        if (prev != null) {
            prev.internalRemoveLabel(label);
            labelCache.remove(label);
        }
        if (version != null) {
            labelCache.put(label, version.getName());
            ((InternalVersionImpl) version).internalAddLabel(label);
        }
        return prev;
    }

    /**
     * Checks in a node. It creates a new version with the given name and freezes
     * the state of the given node.
     *
     * @param name new version name
     * @param src source node to version
     * @return the newly created version
     * @throws RepositoryException if an error occurs
     */
    InternalVersionImpl checkin(Name name, NodeImpl src)
            throws RepositoryException {

        // copy predecessors from src node
        InternalValue[] predecessors;
        if (src.hasProperty(NameConstants.JCR_PREDECESSORS)) {
            Value[] preds = src.getProperty(NameConstants.JCR_PREDECESSORS).getValues();
            predecessors = new InternalValue[preds.length];
            for (int i = 0; i < preds.length; i++) {
                UUID predId = UUID.fromString(preds[i].getString());
                // check if version exist
                if (!nameCache.containsValue(new NodeId(predId))) {
                    throw new RepositoryException("invalid predecessor in source node");
                }
                predecessors[i] = InternalValue.create(predId);
            }
        } else {
            // with simple versioning, the node does not contain a predecessors
            // property and we just use the 'head' version as predecessor
            Iterator iter = nameCache.values().iterator();
            NodeId last = null;
            while (iter.hasNext()) {
                last = (NodeId) iter.next();
            }
            if (last == null) {
                // should never happen
                last = rootVersion.getId();
            }
            predecessors = new InternalValue[]{InternalValue.create(last.getUUID())};
        }

        NodeId versionId = new NodeId(UUID.randomUUID());
        NodeStateEx vNode = node.addNode(name, NameConstants.NT_VERSION, versionId, true);

        // initialize 'created', 'predecessors' and 'successors'
        vNode.setPropertyValue(NameConstants.JCR_CREATED, InternalValue.create(getCurrentTime()));
        vNode.setPropertyValues(NameConstants.JCR_PREDECESSORS, PropertyType.REFERENCE, predecessors);
        vNode.setPropertyValues(NameConstants.JCR_SUCCESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);

        // checkin source node
        InternalFrozenNodeImpl.checkin(vNode, NameConstants.JCR_FROZENNODE, src);

        // update version graph
        InternalVersionImpl version = new InternalVersionImpl(this, vNode, name);
        version.internalAttach();

        // and store
        node.store();

        vMgr.versionCreated(version);

        // update cache
        versionCache.put(version.getId(), version);
        nameCache.put(version.getName(), version.getId());

        return version;
    }

    /**
     * Creates a new version history below the given parent node and with
     * the given name.
     *
     * @param vMgr version manager
     * @param parent parent node
     * @param name history name
     * @param nodeState node state
     * @return new node state
     * @throws RepositoryException if an error occurs
     */
    static NodeStateEx create(
            AbstractVersionManager vMgr, NodeStateEx parent, Name name,
            NodeState nodeState) throws RepositoryException {

        // create history node
        NodeId historyId = new NodeId(UUID.randomUUID());
        NodeStateEx pNode = parent.addNode(name, NameConstants.NT_VERSIONHISTORY, historyId, true);

        // set the versionable uuid
        String versionableUUID = nodeState.getNodeId().getUUID().toString();
        pNode.setPropertyValue(NameConstants.JCR_VERSIONABLEUUID, InternalValue.create(versionableUUID));

        // create label node
        pNode.addNode(NameConstants.JCR_VERSIONLABELS, NameConstants.NT_VERSIONLABELS, null, false);

        // create root version
        NodeId versionId = new NodeId(UUID.randomUUID());
        NodeStateEx vNode = pNode.addNode(NameConstants.JCR_ROOTVERSION, NameConstants.NT_VERSION, versionId, true);

        // initialize 'created' and 'predecessors'
        vNode.setPropertyValue(NameConstants.JCR_CREATED, InternalValue.create(getCurrentTime()));
        vNode.setPropertyValues(NameConstants.JCR_PREDECESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);
        vNode.setPropertyValues(NameConstants.JCR_SUCCESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);

        // add also an empty frozen node to the root version
        NodeStateEx node = vNode.addNode(NameConstants.JCR_FROZENNODE, NameConstants.NT_FROZENNODE, null, true);

        // initialize the internal properties
        node.setPropertyValue(NameConstants.JCR_FROZENUUID, InternalValue.create(versionableUUID));
        node.setPropertyValue(NameConstants.JCR_FROZENPRIMARYTYPE,
                InternalValue.create(nodeState.getNodeTypeName()));

        Set mixins = nodeState.getMixinTypeNames();
        if (mixins.size() > 0) {
            InternalValue[] ivalues = new InternalValue[mixins.size()];
            Iterator iter = mixins.iterator();
            for (int i = 0; i < mixins.size(); i++) {
                ivalues[i] = InternalValue.create((Name) iter.next());
            }
            node.setPropertyValues(NameConstants.JCR_FROZENMIXINTYPES, PropertyType.NAME, ivalues);
        }

        parent.store();
        return pNode;
    }

    /**
     * Returns the current time as a calendar instance and makes sure that no
     * two Calendar instances represent the exact same time. If this method is
     * called quickly in succession each Calendar instance returned is at least
     * one millisecond later than the previous one.
     *
     * @return the current time.
     */
    static Calendar getCurrentTime() {
        long time = System.currentTimeMillis();
        synchronized (CURRENT_TIME) {
            if (time > CURRENT_TIME.getTimeInMillis()) {
                CURRENT_TIME.setTimeInMillis(time);
            } else {
                CURRENT_TIME.add(Calendar.MILLISECOND, 1);
            }
            return (Calendar) CURRENT_TIME.clone();
        }
    }
}
