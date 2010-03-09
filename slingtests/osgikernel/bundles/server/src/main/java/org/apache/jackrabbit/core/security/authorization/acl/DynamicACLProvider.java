/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.jackrabbit.core.security.authorization.acl;

import org.apache.commons.collections.iterators.IteratorChain;
import org.apache.commons.collections.map.LRUMap;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.core.DynamicSecurityManager;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.id.NodeId;
import org.apache.jackrabbit.core.security.authorization.AccessControlConstants;
import org.apache.jackrabbit.core.security.authorization.CompiledPermissions;
import org.apache.sling.jcr.jackrabbit.server.security.dynamic.DynamicPrincipalManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.AccessDeniedException;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

/**
 * Extension of the standard ACLProvider to use a dynamic entry collector.
 */
public class DynamicACLProvider extends ACLProvider {

  private static final Logger LOG = LoggerFactory.getLogger(DynamicACLProvider.class);
  private String userId;
  private DynamicPrincipalManager dynamicPrincipalManager;
  private LRUMap staticPrincipals = new LRUMap(1000);
  private NodeId rootNodeId;

  

  /**
   * @param dynamicPrincipalManager2
   */
  public DynamicACLProvider(DynamicPrincipalManager dynamicPrincipalManager) {
    this.dynamicPrincipalManager = dynamicPrincipalManager;
  }

  /**
   * {@inheritDoc}
   * 
   * @see org.apache.sling.jcr.jackrabbit.server.impl.security.standard.ACLProvider#init(javax.jcr.Session,
   *      java.util.Map)
   */
  @SuppressWarnings("unchecked")
  @Override
  public void init(Session systemSession, Map configuration) throws RepositoryException {
    super.init(systemSession, configuration);
    NodeImpl node = (NodeImpl) systemSession.getRootNode();
    rootNodeId = node.getNodeId();
  }

  /**
   * {@inheritDoc}
   * @see org.apache.jackrabbit.core.security.authorization.acl.ACLProvider#compilePermissions(java.util.Set)
   */
  @Override
  public CompiledPermissions compilePermissions(Set<Principal> principals)
      throws RepositoryException {
    userId = DynamicSecurityManager.getThreadBoundAMContext().getSession().getUserID();
    return super.compilePermissions(principals);
  }
  /**
   * {@inheritDoc}
   * 
   * @see org.apache.sling.jcr.jackrabbit.server.impl.security.standard.ACLProvider#retrieveResultEntries(org.apache.jackrabbit.core.NodeImpl,
   *      java.util.List)
   */
  @Override
  protected Iterator<AccessControlEntry> retrieveResultEntries(NodeImpl node,
      List<String> principalNames) throws RepositoryException {
    return new Entries(node, principalNames).iterator();
  }

  /**
   * Inner class used to collect ACEs for a given set of principals throughout the node
   * hierarchy.
   */
  private class Entries {

    private final Collection<String> principalNames;
    private final List<AccessControlEntry> userAces = new ArrayList<AccessControlEntry>();
    private final List<AccessControlEntry> groupAces = new ArrayList<AccessControlEntry>();

    private Entries(NodeImpl node, Collection<String> principalNames)
        throws RepositoryException {
      this.principalNames = principalNames;
      collectEntries(node, node);
      
    }

    private void collectEntries(NodeImpl node, NodeImpl contextNode) throws RepositoryException {
      // if the given node is access-controlled, construct a new ACL and add
      // it to the list
      if (isAccessControlled(node)) {
        // build acl for the access controlled node
        NodeImpl aclNode = node.getNode(N_POLICY);
        // collectEntries(aclNode, principalNamesToEntries);
        collectEntriesFromAcl(aclNode, contextNode);
      }
      // recursively look for access controlled parents up the hierarchy.
      if (!rootNodeId.equals(node.getId())) {
        NodeImpl parentNode = (NodeImpl) node.getParent();
        collectEntries(parentNode, contextNode);
      }
    }

    /**
     * Separately collect the entries defined for the user and group principals.
     * 
     * @param aclNode
     *          acl node
     * @throws RepositoryException
     *           if an error occurs
     */
    private void collectEntriesFromAcl(NodeImpl aclNode, NodeImpl contextNode) throws RepositoryException {
      SessionImpl sImpl = (SessionImpl) aclNode.getSession();
      PrincipalManager principalMgr = sImpl.getPrincipalManager();
      AccessControlManager acMgr = sImpl.getAccessControlManager();

      // first collect aces present on the given aclNode.
      List<AccessControlEntry> gaces = new ArrayList<AccessControlEntry>();
      List<AccessControlEntry> uaces = new ArrayList<AccessControlEntry>();

      NodeIterator itr = aclNode.getNodes();
      while (itr.hasNext()) {
        NodeImpl aceNode = (NodeImpl) itr.nextNode();

        String principalName = aceNode.getProperty(
            AccessControlConstants.P_PRINCIPAL_NAME).getString();
        // only process aceNode if 'principalName' is contained in the given set
        // or the dynamicPrincialManager says the user has the principal.

        if (principalNames.contains(principalName)
            || hasPrincipal(principalName, aclNode, contextNode, 
                userId)) {
          Principal princ = principalMgr.getPrincipal(principalName);

          Value[] privValues = aceNode.getProperty(AccessControlConstants.P_PRIVILEGES)
              .getValues();
          Privilege[] privs = new Privilege[privValues.length];
          for (int i = 0; i < privValues.length; i++) {
            privs[i] = acMgr.privilegeFromName(privValues[i].getString());
          }
          // create a new ACEImpl (omitting validation check)
          AccessControlEntry ace = new ACLTemplate.Entry(princ, privs, aceNode
              .isNodeType(AccessControlConstants.NT_REP_GRANT_ACE), sImpl
              .getValueFactory());
          // add it to the proper list (e.g. separated by principals)
          /**
           * NOTE: access control entries must be collected in reverse order in order to
           * assert proper evaluation.
           */
          if (princ instanceof Group) {
            gaces.add(0, ace);
          } else {
            uaces.add(0, ace);
          }
        }
      }

      // add the lists of aces to the overall lists that contain the entries
      // throughout the hierarchy.
      if (!gaces.isEmpty()) {
        groupAces.addAll(gaces);
      }
      if (!uaces.isEmpty()) {
        userAces.addAll(uaces);
      }
    }

    @SuppressWarnings("unchecked")
    private Iterator<AccessControlEntry> iterator() {
      return new IteratorChain(userAces.iterator(), groupAces.iterator());
    }
  }

  protected boolean hasPrincipal(String principalName, NodeImpl aclNode, NodeImpl contextNode,
       String userId) {
    /*
     * Principals that don't have a 'dynamic=true' property will not be resolved
     * dynamically. We cache principals that are found not to be dynamic. The
     * cache is never invalidated because it is assumed that principals will not
     * be included in ACLs until their dynamic/static status has been set, and
     * that setting will not be modified subsequently.
     */
    if (staticPrincipals.containsKey(principalName)) {
      LOG.debug("Principal " + principalName + " is cached static - not resolving dynamically");
      return false;
    }
    Session session = aclNode.getSession();
    if (session instanceof JackrabbitSession) {
      JackrabbitSession jcrSession = (JackrabbitSession) session;
      try {
        boolean dynamic = false;
        UserManager manager = jcrSession.getUserManager();
        Authorizable principal = manager.getAuthorizable(principalName);
        if ( principal == null ) {
          return false;
        } else if (principal.hasProperty("dynamic")) {
          Value[] dyn = principal.getProperty("dynamic");
          if (dyn != null && dyn.length > 0 && ("true".equals(dyn[0].getString()))) {
            LOG.debug("Found dynamic principal " + principalName);
            dynamic = true;
          }
        }
        if (!dynamic) {
          LOG.debug("Found static principal " + principalName + ". Caching");
          staticPrincipals.put(principalName, true);
          return false;
        }
      } catch (AccessDeniedException e) {
        LOG.error("Unable to determine group status", e);
      } catch (UnsupportedRepositoryOperationException e) {
        LOG.error("Unable to access user manager", e);
      } catch (RepositoryException e) {
        LOG.error("Unable to access user manager", e);
      }
    }
    return dynamicPrincipalManager.hasPrincipalInContext(principalName, aclNode, contextNode, userId);
  }

}