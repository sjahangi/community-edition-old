package com.activiti.repo.node;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;

import com.activiti.repo.dictionary.ClassRef;
import com.activiti.repo.dictionary.NamespaceService;
import com.activiti.repo.dictionary.bootstrap.DictionaryBootstrap;
import com.activiti.repo.domain.hibernate.NodeImpl;
import com.activiti.repo.ref.ChildAssocRef;
import com.activiti.repo.ref.EntityRef;
import com.activiti.repo.ref.NodeRef;
import com.activiti.repo.ref.Path;
import com.activiti.repo.ref.QName;
import com.activiti.repo.ref.StoreRef;
import com.activiti.util.BaseSpringTest;
import com.activiti.util.debug.CodeMonkey;

/**
 * Provides a base set of tests of the various {@link com.activiti.repo.node.NodeService}
 * implementations.
 * <p>
 * To test a specific incarnation of the service, the methods {@link #getStoreService()} and
 * {@link #getNodeService()} must be implemented. 
 * 
 * @see #nodeService
 * @see #rootNodeRef
 * @see #buildNodeGraph()
 * 
 * @author Derek Hulley
 */
public abstract class BaseNodeServiceTest extends BaseSpringTest
{
    protected NodeService nodeService;
    /** populated during setup */
    protected NodeRef rootNodeRef;

    protected void onSetUpInTransaction() throws Exception
    {
        nodeService = getNodeService();
        
        // create a first store directly
        StoreRef storeRef = nodeService.createStore(
                StoreRef.PROTOCOL_WORKSPACE,
                "Test_" + System.currentTimeMillis());
        rootNodeRef = nodeService.getRootNode(storeRef);
    }
    
    /**
     * Usually just implemented by fetching the bean directly from the bean factory,
     * for example:
     * <p>
     * <pre>
     *      return (NodeService) applicationContext.getBean("dbNodeService");
     * </pre>
     * 
     * @return Returns the implementation of <code>NodeService</code> to be
     *      used for this test
     */
    protected abstract NodeService getNodeService();
    
    public void testSetUp() throws Exception
    {
        assertNotNull("StoreService not set", nodeService);
        assertNotNull("NodeService not set", nodeService);
        assertNotNull("rootNodeRef not created", rootNodeRef);
    }
    
    /**
     * Builds a graph of child associations as follows:
     * <pre>
     * Level 0:     root
     * Level 1:     root_p_n1   root_p_n2
     * Level 2:     n1_p_n3     n2_p_n4     n1_n4       n2_p_n5
     * Level 3:     n3_p_n6     n4_n6       n5_p_n7
     * Level 4:     n6_p_n8     n7_n8
     * </pre>
     * The namespace URI for all associations is <b>http://x</b>.
     * <p>
     * The naming convention is:
     * <pre>
     * n2_p_n5
     * n4_n5
     * where
     *      n5 is the node number of the node
     *      n2 is the primary parent node number
     *      n4 is any other non-primary parent
     * </pre>
     * <p>
     * The session is flushed to ensure that persistence occurs correctly.  It is
     * cleared to ensure that fetches against the created data are correct.
     * 
     * @return Returns a map <code>ChildAssocRef</code> instances keyed by qualified assoc name
     */
    protected Map<QName, ChildAssocRef> buildNodeGraph() throws Exception
    {
        String ns = NamespaceService.ACTIVITI_TEST_URI;
        QName qname = null;
        ChildAssocRef assoc = null;
        Map<QName, ChildAssocRef> ret = new HashMap<QName, ChildAssocRef>(13);
        
        // LEVEL 0

        // LEVEL 1
        qname = QName.createQName(ns, "root_p_n1");
        assoc = nodeService.createNode(rootNodeRef, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n1 = assoc.getChildRef();

        qname = QName.createQName(ns, "root_p_n2");
        assoc = nodeService.createNode(rootNodeRef, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n2 = assoc.getChildRef();

        // LEVEL 2
        qname = QName.createQName(ns, "n1_p_n3");
        assoc = nodeService.createNode(n1, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n3 = assoc.getChildRef();

        qname = QName.createQName(ns, "n2_p_n4");
        assoc = nodeService.createNode(n2, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n4 = assoc.getChildRef();

        qname = QName.createQName(ns, "n1_n4");
        assoc = nodeService.addChild(n1, n4, qname);
        ret.put(qname, assoc);

        qname = QName.createQName(ns, "n2_p_n5");
        assoc = nodeService.createNode(n2, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n5 = assoc.getChildRef();

        // LEVEL 3
        qname = QName.createQName(ns, "n3_p_n6");
        assoc = nodeService.createNode(n3, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n6 = assoc.getChildRef();

        qname = QName.createQName(ns, "n4_n6");
        assoc = nodeService.addChild(n4, n6, qname);
        ret.put(qname, assoc);

        qname = QName.createQName(ns, "n5_p_n7");
        assoc = nodeService.createNode(n5, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n7 = assoc.getChildRef();

        // LEVEL 4
        qname = QName.createQName(ns, "n6_p_n8");
        assoc = nodeService.createNode(n6, qname, DictionaryBootstrap.TYPE_FOLDER);
        ret.put(qname, assoc);
        NodeRef n8 = assoc.getChildRef();

        qname = QName.createQName(ns, "n7_n8");
        assoc = nodeService.addChild(n7, n8, qname);
        ret.put(qname, assoc);

        // flush and clear
        getSession().flush();
        getSession().clear();
        
        // done
        return ret;
    }
    
    private int countNodesById(NodeRef nodeRef)
    {
        String query =
                "select count(node.key.guid)" +
                " from " +
                NodeImpl.class.getName() + " node" +
                " where node.key.guid = ?";
        Session session = getSession();
        List results = session.createQuery(query)
            .setString(0, nodeRef.getId())
            .list();
        Integer count = (Integer) results.get(0);
        return count.intValue();
    }
    
    /**
     * @return Returns a reference to the created store
     */
    private StoreRef createStore() throws Exception
    {
        StoreRef storeRef = nodeService.createStore(StoreRef.PROTOCOL_WORKSPACE, "my store");
        assertNotNull("No reference returned", storeRef);
        // done
        return storeRef;
    }
    
    public void testCreateStore() throws Exception
    {
        createStore();
    }
    
    public void testExists() throws Exception
    {
        StoreRef storeRef = createStore();
        boolean exists = nodeService.exists(storeRef);
        assertEquals("Exists failed", true, exists);
        // create bogus ref
        StoreRef bogusRef = new StoreRef("What", "the");
        exists = nodeService.exists(bogusRef);
        assertEquals("Exists failed", false, exists);
    }
    
    public void testGetRootNode() throws Exception
    {
        StoreRef storeRef = createStore();
        // get the root node
        NodeRef rootNodeRef = nodeService.getRootNode(storeRef);
        assertNotNull("No root node reference returned", rootNodeRef);
        // get the root node again
        NodeRef rootNodeRefCheck = nodeService.getRootNode(storeRef);
        assertEquals("Root nodes returned different refs", rootNodeRef, rootNodeRefCheck);
    }

    public void testGetType() throws Exception
    {
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName("pathA"),
                DictionaryBootstrap.TYPE_FOLDER);
        NodeRef nodeRef = assocRef.getChildRef();
        // get the type
        ClassRef type = nodeService.getType(nodeRef);
        assertEquals("Type mismatch", DictionaryBootstrap.TYPE_FOLDER, type);
    }

    public void testCreateNodeNoProperties() throws Exception
    {
        // flush to ensure that the pure JDBC query will work
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName("path1"),
                DictionaryBootstrap.TYPE_FOLDER);
        NodeRef nodeRef = assocRef.getChildRef();
        // count the nodes with the given id
        int count = countNodesById(nodeRef);
        assertEquals("Unexpected number of nodes present", 1, count);
    }
    
    public void testDelete() throws Exception
    {
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName("path1"),
                DictionaryBootstrap.TYPE_FOLDER);
        NodeRef nodeRef = assocRef.getChildRef();
        int countBefore = countNodesById(nodeRef);
        assertEquals("Node not created", 1, countBefore);
        // delete it
        nodeService.deleteNode(nodeRef);
        int countAfter = countNodesById(nodeRef);
        // check
        assertEquals("Node not deleted", 0, countAfter);
    }
    
    private int countChildrenOfNode(NodeRef nodeRef)
    {
        String query =
                "select node.childAssocs" +
                " from " +
                NodeImpl.class.getName() + " node" +
                " where node.key.guid = ?";
        Session session = getSession();
        List results = session.createQuery(query)
            .setString(0, nodeRef.getId())
            .list();
        int count = results.size();
        return count;
    }
    
    public void testAddChild() throws Exception
    {
        // create a bogus reference
        NodeRef bogusChildRef = new NodeRef(rootNodeRef.getStoreRef(), "BOGUS");
        try
        {
            nodeService.addChild(rootNodeRef, bogusChildRef, QName.createQName("BOGUS_PATH"));
            fail("Failed to detect invalid child node reference");
        }
        catch (InvalidNodeRefException e)
        {
            // expected
        }
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName("pathA"),
                DictionaryBootstrap.TYPE_FOLDER);
                CodeMonkey.todo("Fix test checks");
//        int countBefore = countChildrenOfNode(rootNodeRef);
//        assertEquals("Root children count incorrect", 1, countBefore);
        // associate the two nodes
        nodeService.addChild(rootNodeRef, assocRef.getChildRef(), QName.createQName("pathB"));
        // there should now be 2 child assocs on the root
        CodeMonkey.todo("Fix test checks");
//        int countAfter = countChildrenOfNode(rootNodeRef);
//        assertEquals("Root children count incorrect", 2, countAfter);
    }
    
    public void testRemoveChildByRef() throws Exception
    {
        ChildAssocRef pathARef = nodeService.createNode(rootNodeRef,
                QName.createQName("pathA"),
                DictionaryBootstrap.TYPE_FOLDER);
        NodeRef nodeRef = pathARef.getChildRef();
        ChildAssocRef pathBRef = nodeService.addChild(rootNodeRef, nodeRef, QName.createQName("pathB"));
        ChildAssocRef pathCRef = nodeService.addChild(rootNodeRef, nodeRef, QName.createQName("pathC"));
        // delete all the associations
        Collection<EntityRef> deletedRefs = nodeService.removeChild(rootNodeRef, nodeRef);
        assertTrue("Primary child not deleted", deletedRefs.contains(nodeRef));
        assertTrue("Primary A path not deleted", deletedRefs.contains(pathARef));
        assertTrue("Secondary B path not deleted", deletedRefs.contains(pathBRef));
        assertTrue("Secondary C path not deleted", deletedRefs.contains(pathCRef));
    }
    
    public void testRemoveChildByName() throws Exception
    {
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName("nsA", "pathA"),
                DictionaryBootstrap.TYPE_FOLDER);
        NodeRef nodeRef = assocRef.getChildRef();
        nodeService.addChild(rootNodeRef, nodeRef, QName.createQName("nsB1", "pathB"));
        nodeService.addChild(rootNodeRef, nodeRef, QName.createQName("nsB2", "pathB"));
        nodeService.addChild(rootNodeRef, nodeRef, QName.createQName("nsC", "pathC"));
        // delete all the associations
        nodeService.removeChildren(rootNodeRef, QName.createQName("nsB1", "pathB"));
        
        // get the children of the root
        Collection<ChildAssocRef> childAssocRefs = nodeService.getChildAssocs(rootNodeRef);
        assertEquals("Unexpected number of children under root", 3, childAssocRefs.size());
        
        // flush and clear
        flushAndClear();
        
        // get the children again to check that the flushing didn't produce different results
        childAssocRefs = nodeService.getChildAssocs(rootNodeRef);
        assertEquals("Unexpected number of children under root", 3, childAssocRefs.size());
    }
    
    public void testProperties() throws Exception
    {
        Map<QName, Serializable> properties = new HashMap<QName, Serializable>(5);
        properties.put(QName.createQName("PROPERTY1"), "VALUE1");
        // add some properties to the root node
        nodeService.setProperties(rootNodeRef, properties);
        // set a single property
        nodeService.setProperty(rootNodeRef, QName.createQName("PROPERTY2"), "VALUE2");
        
        // force a flush
        getSession().flush();
        getSession().clear();
        
        // now get them back
        Map<QName, Serializable> checkMap = nodeService.getProperties(rootNodeRef);
        assertNotNull("Properties were not set/retrieved", checkMap);
        assertNotNull("Property value not set", checkMap.get(QName.createQName("PROPERTY1")));
        assertNotNull("Property value not set", checkMap.get(QName.createQName("PROPERTY2")));
        
        // get a single property direct from the node
        Serializable valueCheck = nodeService.getProperty(rootNodeRef, QName.createQName("PROPERTY2"));
        assertNotNull("Property value not set", valueCheck);
        assertEquals("Property value incorrect", "VALUE2", valueCheck);
    }
    
    public void testGetParents() throws Exception
    {
        Map<QName, ChildAssocRef> assocRefs = buildNodeGraph();
        NodeRef n6Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n3_p_n6")).getChildRef();
        NodeRef n7Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n5_p_n7")).getChildRef();
        // get a child node's parents
        NodeRef n8Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n6_p_n8")).getChildRef();
        Collection<NodeRef> parents = nodeService.getParents(n8Ref);
        assertEquals("Incorrect number of parents", 2, parents.size());
        assertTrue("Expected parent not found", parents.contains(n6Ref));
        assertTrue("Expected parent not found", parents.contains(n7Ref));
        
        // check that we can retrieve the primary parent
        NodeRef primaryParentCheck = nodeService.getPrimaryParent(n8Ref);
        assertEquals("Primary parent not retrieved", n6Ref, primaryParentCheck);
        
        // check that the root node returns a null primary parent
        NodeRef nullParent = nodeService.getPrimaryParent(rootNodeRef);
        assertNull("Expected null primary parent for root node", nullParent);
    }
    
    public void testGetChildAssocs() throws Exception
    {
        Map<QName, ChildAssocRef> assocRefs = buildNodeGraph();
        NodeRef n1Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"root_p_n1")).getChildRef();
        
        // get the parent node's children
        Collection<ChildAssocRef> childAssocRefs = nodeService.getChildAssocs(n1Ref);
        assertEquals("Incorrect number of children", 2, childAssocRefs.size());
    }
    
    /**
     * Creates a named association between two nodes
     * 
     * @return Returns an array of [source real NodeRef][target reference NodeRef][assoc name String]
     */
    private Object[] createAssociation() throws Exception
    {
        ChildAssocRef assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName(null, "N1"),
                DictionaryBootstrap.TYPE_BASE);
        NodeRef sourceRef = assocRef.getChildRef();
        assocRef = nodeService.createNode(rootNodeRef,
                QName.createQName(null, "N2"),
                DictionaryBootstrap.TYPE_REFERENCE);
        NodeRef targetRef = assocRef.getChildRef();
        
        QName qname = QName.createQName("next");
        nodeService.createAssociation(sourceRef, targetRef, qname);
        // done
        Object[] ret = new Object[] {sourceRef, targetRef, qname};
        return ret;
    }
    
    public void testCreateAssociation() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        QName qname = (QName) ret[2];
        try
        {
            // attempt the association in reverse
            nodeService.createAssociation(sourceRef, targetRef, qname);
            fail("Incorrect node type not detected");
        }
        catch (RuntimeException e)
        {
            // expected
        }
        try
        {
            // attempt repeat
            nodeService.createAssociation(sourceRef, targetRef, qname);
            fail("Duplicate assocation not detected");
        }
        catch (AssociationExistsException e)
        {
            // expected
        }
    }
    
    public void testRemoveAssociation() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        QName qname = (QName) ret[2];
        // remove the association
        nodeService.removeAssociation(sourceRef, targetRef, qname);
        // remake association
        nodeService.createAssociation(sourceRef, targetRef, qname);
    }
    
    public void testGetAssociationTargets() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        QName qname = (QName) ret[2];
        // get the association targets
        Collection<NodeRef> targets = nodeService.getAssociationTargets(sourceRef, qname);
        assertEquals("Incorrect number of targets", 1, targets.size());
        assertTrue("Target not found", targets.contains(targetRef));
    }
    
    public void testGetAssociationSources() throws Exception
    {
        Object[] ret = createAssociation();
        NodeRef sourceRef = (NodeRef) ret[0];
        NodeRef targetRef = (NodeRef) ret[1];
        QName qname = (QName) ret[2];
        // get the association targets
        Collection<NodeRef> sources = nodeService.getAssociationSources(targetRef, qname);
        assertEquals("Incorrect number of sources", 1, sources.size());
        assertTrue("Source not found", sources.contains(sourceRef));
    }
    
    /**
     * @see #buildNodeGraph() 
     */
    public void testGetPath() throws Exception
    {
        Map<QName, ChildAssocRef> assocRefs = buildNodeGraph();
        NodeRef n8Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n6_p_n8")).getChildRef();

        // get the primary node path for n8
        Path path = nodeService.getPath(n8Ref);
        assertEquals("Primary path incorrect",
                "/{" + NamespaceService.ACTIVITI_TEST_URI + "}root_p_n1/{" + NamespaceService.ACTIVITI_TEST_URI + "}n1_p_n3/{" + NamespaceService.ACTIVITI_TEST_URI + "}n3_p_n6/{" + NamespaceService.ACTIVITI_TEST_URI + "}n6_p_n8",
                path.toString());
    }
    
    /**
     * @see #buildNodeGraph() 
     */
    public void testGetPaths() throws Exception
    {
        Map<QName, ChildAssocRef> assocRefs = buildNodeGraph();
        NodeRef n6Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n3_p_n6")).getChildRef();
        NodeRef n8Ref = assocRefs.get(QName.createQName(NamespaceService.ACTIVITI_TEST_URI,"n6_p_n8")).getChildRef();
        
        // get all paths for the root node
        Collection<Path> paths = nodeService.getPaths(rootNodeRef, false);
        assertEquals("Root node must have exactly 1 path", 1, paths.size());
        Path rootPath = paths.toArray(new Path[1])[0];
        assertNotNull("Root node path must have 1 element", rootPath.last());
        assertEquals("Root node path must have 1 element", rootPath.first(), rootPath.last());

        // get all paths for n8
        paths = nodeService.getPaths(n8Ref, false);
        assertEquals("Incorrect path count", 4, paths.size());
        // check that each path element has parent node ref, qname and child node ref
        for (Path path : paths)
        {
            // get the path elements
            for (Path.Element element : path)
            {
                assertTrue("Path element of incorrect type", element instanceof Path.ChildAssocElement);
                Path.ChildAssocElement childAssocElement = (Path.ChildAssocElement) element;
                ChildAssocRef ref = childAssocElement.getRef();
                if (childAssocElement != path.first())
                {
                    // for all but the first element, the parent and assoc qname must be set
                    assertNotNull("Parent node ref not set", ref.getParentRef());
                    assertNotNull("QName not set", ref.getName());
                }
                // all associations must have a child ref
                assertNotNull("Child node ref not set", ref.getChildRef());
            }
        }

        // get primary path for n8
        paths = nodeService.getPaths(n8Ref, true);
        assertEquals("Incorrect path count", 1, paths.size());
        
        // check that a cyclic path is detected - make n8_n2
        try
        {
            nodeService.addChild(n8Ref, n6Ref, QName.createQName("n8_n6"));
            nodeService.getPaths(n8Ref, false);
            fail("Cyclic relationship not detected");
        }
        catch (CyclicChildRelationshipException e)
        {
            // expected
        }
        catch (StackOverflowError e)
        {
            fail("Cyclic relationship caused stack overflow");
        }
    }
}
