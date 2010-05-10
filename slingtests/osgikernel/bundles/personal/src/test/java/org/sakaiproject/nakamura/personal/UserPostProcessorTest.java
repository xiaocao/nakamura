package org.sakaiproject.nakamura.personal;

import static org.easymock.EasyMock.expect;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.servlets.post.Modification;
import org.easymock.classextension.EasyMock;
import org.junit.Test;
import org.sakaiproject.nakamura.api.user.UserConstants;
import org.sakaiproject.nakamura.testutils.easymock.AbstractEasyMockTest;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Value;

public class UserPostProcessorTest extends AbstractEasyMockTest {
  @Test
  public void testNoProcessingNeeded() throws Exception {
    ArrayList<String> propNames = new ArrayList<String>();
    propNames.add("rep:userId");
    Authorizable authorizable = createAuthorizable("admin", false, false);
    expect(authorizable.getPropertyNames()).andReturn(propNames.iterator()).atLeastOnce();
    expect(authorizable.getProperty("rep:userId")).andReturn(new Value[] {}).atLeastOnce();
    EasyMock.replay(authorizable);
    Node profileNode = createMock(Node.class);
    Property property = createNiceMock(Property.class);
    expect(property.getString()).andReturn(UserConstants.USER_PROFILE_RESOURCE_TYPE);
    expect(profileNode.hasProperty("sling:resourceType")).andReturn(true);
    expect(profileNode.getProperty("sling:resourceType")).andReturn(property);
    JackrabbitSession session = createMock(JackrabbitSession.class);
    expect(session.nodeExists("/_user/a/ad/admin/public/authprofile")).andReturn(true).atLeastOnce();
    expect(session.getNode("/_user/a/ad/admin/public/authprofile")).andReturn(profileNode).atLeastOnce();
    SlingHttpServletRequest request = createMock(SlingHttpServletRequest.class);
    List<Modification> changes = new ArrayList<Modification>();
    replay();
    PersonalUserPostProcessor postProcessor = new PersonalUserPostProcessor();
    postProcessor.process(authorizable, session, request, changes);
    verify();
  }
}
