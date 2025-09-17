package com.example;

import gov.loc.premis.v3.ObjectFactory;
import gov.loc.premis.v3.PremisComplexType;

import javax.xml.bind.*;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * PremisCombinedGenerator
 *
 * Builds a single PREMIS v3 XML (intellectualObject, agents, rights, file objects, relationships)
 * using the generated gov.loc.premis.v3 JAXB classes.
 *
 * Usage:
 *   java -cp <classpath> com.example.PremisCombinedGenerator <sip-root> [<out-file>]
 *
 * Notes:
 * - This class is resilient to differences in generated ObjectFactory API.
 * - It sets size as BigInteger when possible, adds fixity (SHA-256), formatDesignation->formatName,
 *   and an objectCharacteristicsExtension/receivingDate.
 */
public class PremisCombinedGenerator {
    private static final Logger LOG = Logger.getLogger(PremisCombinedGenerator.class.getName());
    private static final String PREMIS_NS = "http://www.loc.gov/premis/v3";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path sipRoot;
    private final JAXBContext jaxb;
    private final ObjectFactory factory;

    // strong typed root
    private PremisComplexType premisRoot;
    private JAXBElement<PremisComplexType> premisElement;

    public PremisCombinedGenerator(Path sipRoot) throws Exception {
        if (sipRoot == null || !Files.isDirectory(sipRoot)) {
            throw new IllegalArgumentException("sipRoot must be an existing directory");
        }
        this.sipRoot = sipRoot;
        this.jaxb = JAXBContext.newInstance("gov.loc.premis.v3");
        this.factory = new ObjectFactory();
        initPremisRoot();
    }

    private void initPremisRoot() throws Exception {
        // Try factory createPremis methods
        try {
            Method m = factory.getClass().getMethod("createPremis");
            Object r = m.invoke(factory);
            if (r instanceof JAXBElement) {
                @SuppressWarnings("unchecked")
                JAXBElement<PremisComplexType> je = (JAXBElement<PremisComplexType>) r;
                this.premisElement = je;
                this.premisRoot = je.getValue();
                return;
            }
        } catch (NoSuchMethodException ignored) { }
        try {
            Method m2 = factory.getClass().getMethod("createPremis", PremisComplexType.class);
            PremisComplexType tmp = new PremisComplexType();
            @SuppressWarnings("unchecked")
            JAXBElement<PremisComplexType> je2 = (JAXBElement<PremisComplexType>) m2.invoke(factory, tmp);
            this.premisElement = je2;
            this.premisRoot = je2.getValue();
            return;
        } catch (NoSuchMethodException ignored) { }

        // fallback: create new root and wrap in JAXBElement
        this.premisRoot = new PremisComplexType();
        this.premisElement = new JAXBElement<>(new QName(PREMIS_NS, "premis"), PremisComplexType.class, this.premisRoot);
    }

    /**
     * Main orchestration method: builds all sections and saves XML.
     */
   /**
     * Top-level orchestration to build all parts and save the PREMIS XML.
     */
    public void generateAndSave(Path outFile) throws Exception {
        // Build sections (order: intellect -> agents/rights -> files -> relationships)
        try {
            buildIntellectualObject();     // create & attach intellectualObject/intellectualEntity
        } catch (Throwable t) {
            LOG.warning("buildIntellectualObject failed: " + t.getMessage());
        }

        try {
            buildAgentsAndRights();        // create & attach agents and rights
        } catch (Throwable t) {
            LOG.warning("buildAgentsAndRights failed: " + t.getMessage());
        }

        try {
            scanFilesAndAddObjects();      // scan SIP and add file objects (and per-file relationships)
        } catch (Throwable t) {
            LOG.warning("scanFilesAndAddObjects failed: " + t.getMessage());
        }

        try {
            buildRelationships();          // build top-level relationships block (hasRepresentation, hasMetadata...)
        } catch (Throwable t) {
            LOG.warning("buildRelationships failed: " + t.getMessage());
        }

        // Some generated bindings expose different getter names on the premis root.
        // Ensure premisRoot actually contains the sections we just created: introspect and log if missing.
        verifyAndLogRootContents();

        // Finally marshal to disk
        dumpPremisRootDiagnostics();
        marshal(outFile);
    }


    // ------------------- Build sections -------------------

    private void buildIntellectualObject() throws Exception {
        // Create IntellectualEntity (your binding has this class)
        Object entity = createUsingFactoryOrUnmarshal(
            new String[]{"createIntellectualEntity","createIntellectualObject"},
            gov.loc.premis.v3.IntellectualEntity.class,
            "intellectualEntity"
        );
        if (entity == null) {
            LOG.warning("Unable to create IntellectualEntity");
            return;
        }

        // Object identifier
        Object id = createUsingFactoryOrUnmarshal(
            new String[]{"createObjectIdentifierComplexType","createObjectIdentifier"},
            gov.loc.premis.v3.ObjectIdentifierComplexType.class,
            "objectIdentifier"
        );
        gov.loc.premis.v3.StringPlusAuthority type = new gov.loc.premis.v3.StringPlusAuthority();
        type.setValue("CNR");
        callSetterOrAdd(id, "ObjectIdentifierType", type);
        callSetterOrAdd(id, "ObjectIdentifierValue", sipRoot.getFileName().toString());

        callGetterAndAdd(entity, "ObjectIdentifier", id);

        // Attach entity to premisRoot using type-based attachment
        boolean ok = attachToRootByValueType(entity);
        if (!ok) {
            LOG.warning("Failed to attach IntellectualEntity to premis root via type-based attach.");
        } else {
            LOG.info("Attached IntellectualEntity for CNR: " + sipRoot.getFileName());
        }
    }




    private void buildAgentsAndRights() throws Exception {
        // Agent JTDR (system)
        Object agent = createUsingFactoryOrUnmarshal(new String[]{"createAgentComplexType"}, gov.loc.premis.v3.AgentComplexType.class, "agent");
        Object aid = createUsingFactoryOrUnmarshal(new String[]{"createAgentIdentifierComplexType"}, gov.loc.premis.v3.AgentIdentifierComplexType.class, "agentIdentifier");
        callSetterOrAdd(aid, "AgentIdentifierType", createStringPlusAuthority("system", null));
        callSetterOrAdd(aid, "AgentIdentifierValue", "JTDR");
        callGetterAndAdd(agent, "AgentIdentifier", aid);
        callGetterAndAdd(agent, "AgentName", "JTDR");
        callSetterOrAdd(agent, "AgentType", "software");
        callGetterAndAdd(premisRoot, "Agent", agent);

        // Agent depositor (human)
        Object depos = createUsingFactoryOrUnmarshal(new String[]{"createAgentComplexType"}, gov.loc.premis.v3.AgentComplexType.class, "agent");
        Object daid = createUsingFactoryOrUnmarshal(new String[]{"createAgentIdentifierComplexType"}, gov.loc.premis.v3.AgentIdentifierComplexType.class, "agentIdentifier");
        callSetterOrAdd(daid, "AgentIdentifierType", createStringPlusAuthority("depositor", null));
        callSetterOrAdd(daid, "AgentIdentifierValue", "uploader@example.org");
        callGetterAndAdd(depos, "AgentIdentifier", daid);
        callGetterAndAdd(depos, "AgentName", "Case Uploader");
        callSetterOrAdd(depos, "AgentType", "human");
        callGetterAndAdd(premisRoot, "Agent", depos);

        // Rights
        Object rights = createUsingFactoryOrUnmarshal(new String[]{"createRightsComplexType"}, gov.loc.premis.v3.RightsComplexType.class, "rights");
        Object rs = createUsingFactoryOrUnmarshal(new String[]{"createRightsStatementComplexType"}, gov.loc.premis.v3.RightsStatementComplexType.class, "rightsStatement");
        // rightsBasis may accept String or StringPlusAuthority
        if (!callSetterOrAdd(rs, "RightsBasis", "statute")) {
            callSetterOrAdd(rs, "RightsBasis", createStringPlusAuthority("statute", null));
        }
        callGetterAndAdd(rs, "RightsGranted", "Access restricted to authorized user");
        callGetterAndAdd(rights, "RightsStatement", rs);
        callGetterAndAdd(premisRoot, "Rights", rights);
    }

    private void scanFilesAndAddObjects() throws Exception {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(sipRoot, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });

        // Map files to expected logical paths (metadata, rep1, rep2, schema)
        Path metadataFile = findFirstWhere(files, p -> p.getFileName().toString().toLowerCase().contains("metadata"));
        List<Path> rep1 = findAllUnder(files, sipRoot.resolve("representation").resolve("rep1").resolve("data"));
        if (rep1.isEmpty()) {
            // fallback: first two PDFs found anywhere
            List<Path> pdfs = filterByExt(files, ".pdf");
            pdfs.sort(Comparator.comparing(Path::toString));
            for (int i = 0; i < Math.min(2, pdfs.size()); i++) rep1.add(pdfs.get(i));
        }
        // rep2: use actual files under rep2/data if present
        List<Path> rep2 = findAllUnder(files, sipRoot.resolve("representation").resolve("rep2").resolve("data"));
        Path schema = findFirstUnderExtension(files, ".xsd");

        // Add metadata object
        if (metadataFile != null) {
            String rel = "data/metadata/ODHC012342025_Metadata_ecourt.xml";
            addFileObject(metadataFile, rel, "XML", true, "JTDR", true);
        }

        // Add rep1 originals
        for (int i = 0; i < rep1.size(); i++) {
            Path p = rep1.get(i);
            String rel = String.format("data/representation/rep1/ODHC010879122024_%d.pdf", i + 1);
            boolean createApp = (i == 1); // second original has creatingApplication in your example
            addFileObject(p, rel, "PDF", createApp, "JTDR", true);
        }

        // Add rep2 entries — if real files exist under rep2 use them else synthesize from rep1
        if (!rep2.isEmpty()) {
            for (int i = 0; i < rep2.size(); i++) {
                Path p = rep2.get(i);
                String rel = String.format("data/representation/rep2/ODHC010879122024_%d_converted.pdf", i + 1);
                addFileObject(p, rel, "PDF/A-1B", true, "JTDR", false);
            }
        } else {
            for (int i = 0; i < rep1.size(); i++) {
                Path src = rep1.get(i);
                String rel = String.format("data/representation/rep2/ODHC010879122024_%d_converted.pdf", i + 1);
                Object obj = addFileObject(src, rel, "PDF/A-1B", true, "JTDR", false);

                // Build a RelationshipComplexType and a RelatedObjectIdentifierComplexType (both exist in your generated package)
                Object relObj = createUsingFactoryOrUnmarshal(
                    new String[] {"createRelationshipComplexType","createRelationship"},
                    gov.loc.premis.v3.RelationshipComplexType.class,
                    "relationship"
                );
                if (relObj == null) {
                    LOG.warning("Unable to create RelationshipComplexType instance; skipping derivedFrom relationship for " + rel);
                    continue;
                }

                Object roi = createUsingFactoryOrUnmarshal(
                    new String[] {"createRelatedObjectIdentifierComplexType","createRelatedObjectIdentifier"},
                    gov.loc.premis.v3.RelatedObjectIdentifierComplexType.class,
                    "relatedObjectIdentifier"
                );
                if (roi == null) {
                    LOG.warning("Unable to create RelatedObjectIdentifierComplexType instance; skipping derivedFrom relationship for " + rel);
                    continue;
                }

                // populate ROI
                callSetterOrAdd(roi, "RelatedObjectIdentifierType", "FilePath");
                callSetterOrAdd(roi, "RelatedObjectIdentifierValue", String.format("data/representation/rep1/ODHC010879122024_%d.pdf", i + 1));

                boolean attached = false;

                // Attempt 1: If ObjectFactory has a createRelationshipElement(...) method, use it
                try {
                    // try factory.createRelationshipElement(RelatedObjectIdentifierComplexType)
                    Method facMethod = null;
                    for (Method m : factory.getClass().getMethods()) {
                        if (m.getName().equalsIgnoreCase("createRelationshipElement") && m.getParameterCount() == 1) {
                            facMethod = m;
                            break;
                        }
                    }
                    if (facMethod != null) {
                        Object relElem = facMethod.invoke(factory, roi);
                        // If factory returned JAXBElement, extract or add as is
                        if (relElem instanceof JAXBElement) {
                            Object val = ((JAXBElement<?>) relElem).getValue();
                            // try set RelationshipSubType on wrapper if possible
                            try { callSetterOrAdd(val, "RelationshipSubType", "derivedFrom"); } catch (Throwable ignored) {}
                            callGetterAndAdd(relObj, "RelationshipElement", val);
                        } else {
                            try { callSetterOrAdd(relElem, "RelationshipSubType", "derivedFrom"); } catch (Throwable ignored) {}
                            callGetterAndAdd(relObj, "RelationshipElement", relElem);
                        }
                        attached = true;
                    }
                } catch (Throwable t) {
                    LOG.fine("factory.createRelationshipElement invocation failed: " + t.getMessage());
                }

                // Attempt 2: try to create a relationshipElement instance via createUsingFactoryOrUnmarshal
                if (!attached) {
                    try {
                        // try a few likely class/type names that might exist in some bindings
                        String[] candidates = new String[] {
                            "gov.loc.premis.v3.RelationshipElementComplexType",
                            "gov.loc.premis.v3.RelationshipElementType",
                            "gov.loc.premis.v3.RelationshipElement"
                        };
                        Object relElemCandidate = null;
                        for (String cn : candidates) {
                            try {
                                Class<?> c = Class.forName(cn);
                                // use the generic helper via raw cast to avoid compile-time generic inference issues
                                relElemCandidate = createUsingFactoryOrUnmarshal(new String[] {"createRelationshipElement","createRelationshipElementComplexType"}, (Class) c, "relationshipElement");
                                if (relElemCandidate != null) break;
                            } catch (ClassNotFoundException cnf) {
                                // try next candidate
                            } catch (Throwable t) {
                                LOG.fine("candidate createUsingFactoryOrUnmarshal for " + cn + " failed: " + t.getMessage());
                            }
                        }
                        if (relElemCandidate != null) {
                            // set subtype and add ROI inside it
                            callSetterOrAdd(relElemCandidate, "RelationshipSubType", "derivedFrom");
                            callGetterAndAdd(relElemCandidate, "RelatedObjectIdentifier", roi);
                            callGetterAndAdd(relObj, "RelationshipElement", relElemCandidate);
                            attached = true;
                        }
                    } catch (Throwable t) {
                        LOG.fine("relationshipElement candidate creation failed: " + t.getMessage());
                    }
                }

                // Attempt 3 (fallback): add the RelatedObjectIdentifier directly to RelationshipComplexType
                if (!attached) {
                    try {
                        // Many JAXB bindings will accept adding the inner RelatedObjectIdentifier instance directly
                        // to the RelationshipComplexType RelationshipElement list and marshal it as <relationshipElement>
                        callGetterAndAdd(relObj, "RelationshipElement", roi);
                        // If we can also set a RelationshipSubType directly on relObj (some bindings support short-cuts), try:
                        try { callSetterOrAdd(relObj, "RelationshipSubType", "derivedFrom"); } catch (Throwable ignored) {}
                        attached = true;
                    } catch (Throwable t) {
                        LOG.warning("Failed to attach RelatedObjectIdentifier to RelationshipComplexType: " + t.getMessage());
                    }
                }

                // Attach relationship to the object we created earlier (obj)
                if (attached) {
                    try {
                        callSetterOrAdd(obj, "Relationship", relObj);
                    } catch (Throwable t) {
                        // fallback: add to premis root relationships collection
                        LOG.fine("callSetterOrAdd(obj,\"Relationship\",relObj) failed: " + t.getMessage());
                        callGetterAndAdd(premisRoot, "Relationship", relObj);
                    }
                } else {
                    LOG.warning("Could not attach a proper relationshipElement (with subtype) for derivedFrom for " + rel);
                }
            }
        }

        // schema
        if (schema != null) {
            addFileObject(schema, "data/schema/ecourt.xsd", "XSD", false, "JTDR", false);
        }
    }


    @SuppressWarnings("unchecked")
    private Object createUsingFactoryOrUnmarshalRaw(String[] candidateFactoryMethodNames, Class<?> clazz, String xmlLocalName) {
        // delegate to the generic method using raw cast
        return createUsingFactoryOrUnmarshal(candidateFactoryMethodNames, (Class) clazz, xmlLocalName);
    }


    private void buildRelationships() throws Exception {
    Object relRoot = createUsingFactoryOrUnmarshal(
        new String[]{"createRelationshipComplexType","createRelationship"},
        gov.loc.premis.v3.RelationshipComplexType.class,
        "relationship"
    );
    if (relRoot == null) {
        LOG.warning("Unable to create RelationshipComplexType; skipping relationships");
        return;
    }

    // Create two relatedObjectIdentifier entries and attach into relRoot
    String[] relValues = new String[] {"representation/rep1/", "representation/rep2/"};
    for (String value : relValues) {
        Object roi = createUsingFactoryOrUnmarshal(
            new String[]{"createRelatedObjectIdentifierComplexType","createRelatedObjectIdentifier"},
            gov.loc.premis.v3.RelatedObjectIdentifierComplexType.class,
            "relatedObjectIdentifier"
        );
        if (roi == null) {
            LOG.warning("Unable to create RelatedObjectIdentifierComplexType for " + value);
            continue;
        }
        callSetterOrAdd(roi, "RelatedObjectIdentifierType", value.endsWith("/") ? "directory" : "FilePath");
        callSetterOrAdd(roi, "RelatedObjectIdentifierValue", value);

        // Try factory.createRelationshipElement(roi) if available
        boolean attachedInner = false;
        try {
            Method facMethod = null;
            for (Method m : factory.getClass().getMethods()) {
                if (m.getName().equalsIgnoreCase("createRelationshipElement") && m.getParameterCount() == 1) {
                    facMethod = m;
                    break;
                }
            }
            if (facMethod != null) {
                Object relElem = facMethod.invoke(factory, roi);
                if (relElem instanceof JAXBElement) {
                    Object val = ((JAXBElement<?>) relElem).getValue();
                    callSetterOrAdd(val, "RelationshipSubType", "hasRepresentation");
                    callGetterAndAdd(relRoot, "RelationshipElement", val);
                } else {
                    callSetterOrAdd(relElem, "RelationshipSubType", "hasRepresentation");
                    callGetterAndAdd(relRoot, "RelationshipElement", relElem);
                }
                attachedInner = true;
            }
        } catch (Throwable t) {
            LOG.fine("factory.createRelationshipElement invocation failed: " + t.getMessage());
        }

        // If that didn't work, add roi directly to relRoot
        if (!attachedInner) {
            callGetterAndAdd(relRoot, "RelationshipElement", roi);
        }
    }

    // Now attach relRoot to premisRoot using type-based attachment
    boolean ok = attachToRootByValueType(relRoot);
    if (!ok) {
        LOG.warning("Failed to attach relationship root to premisRoot via type-based attach.");
    } else {
        LOG.info("Attached Relationship block to premisRoot");
    }
}

private void dumpPremisRootDiagnostics() {
    try {
        System.out.println("=== Diagnostics: premisRoot vs premisElement.getValue() ===");

        PremisComplexType rootFromField = this.premisRoot;
        PremisComplexType rootFromElem = (this.premisElement != null) ? this.premisElement.getValue() : null;

        System.out.println("premisRoot == premisElement.getValue()? " + (rootFromField == rootFromElem));

        // Inspect fields/getters on the premisRoot instance
        Object target = (rootFromElem != null) ? rootFromElem : rootFromField;
        if (target == null) {
            System.out.println("Both premisRoot and premisElement.getValue() are null — initialization problem.");
            return;
        }

        Class<?> cls = target.getClass();
        System.out.println("Inspecting instance of: " + cls.getName());

        for (Method m : cls.getMethods()) {
            if (!m.getName().startsWith("get")) continue;
            try {
                Object val = m.invoke(target);
                if (val == null) continue;
                if (val instanceof Collection) {
                    Collection<?> c = (Collection<?>) val;
                    System.out.printf("  %s -> Collection size=%d (element sample: %s)%n",
                            m.getName(), c.size(), (c.isEmpty() ? "empty" : c.iterator().next().getClass().getName()));
                } else {
                    System.out.printf("  %s -> %s%n", m.getName(), val.getClass().getName());
                }
            } catch (Throwable t) {
                // ignore occasional getters that require args
            }
        }

        // Also print a short XML preview to see the real output (first 400 chars)
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Marshaller mm = jaxb.createMarshaller();
            mm.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            JAXBElement<PremisComplexType> rootElem = this.premisElement;
            if (rootElem == null) rootElem = new JAXBElement<>(new QName(PREMIS_NS, "premis"), PremisComplexType.class, this.premisRoot);
            mm.marshal(rootElem, baos);
            String xml = baos.toString("UTF-8");
            System.out.println("=== XML (first 800 chars) ===");
            System.out.println(xml.substring(0, Math.min(xml.length(), 800)));
        } catch (Throwable t) {
            System.out.println("Failed to produce XML preview: " + t.getMessage());
        }

    } catch (Throwable e) {
        System.out.println("Diagnostics error: " + e.getMessage());
        e.printStackTrace(System.out);
    }
}


/**
 * Attach 'value' (usually a generated JAXB complex-type instance) to premisRoot by finding
 * a suitable Collection property on premisRoot whose element type matches value's class.
 * Returns true on success.
 */
private boolean attachToRootByValueType(Object value) {
    if (premisRoot == null || value == null) return false;
    Class<?> vClass = value.getClass();

    try {
        // 1) Look for getXxx() methods returning Collection<T> where T is assignable from vClass
        for (Method m : premisRoot.getClass().getMethods()) {
            if (!m.getName().startsWith("get")) continue;
            if (!Collection.class.isAssignableFrom(m.getReturnType())) continue;

            // inspect generic return type
            try {
                java.lang.reflect.Type retType = m.getGenericReturnType();
                if (retType instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.Type[] args = ((java.lang.reflect.ParameterizedType) retType).getActualTypeArguments();
                    if (args != null && args.length == 1) {
                        String argTypeName = args[0].getTypeName();
                        // If generic parameter is a class name assignable from value or equals the value class, use it
                        try {
                            Class<?> elementClass = Class.forName(argTypeName);
                            if (elementClass.isAssignableFrom(vClass)) {
                                // add to collection
                                Object col = m.invoke(premisRoot);
                                if (col instanceof Collection) {
                                    ((Collection) col).add(value);
                                    return true;
                                }
                            }
                        } catch (ClassNotFoundException cnf) {
                            // generic arg may be an interface or different naming; we'll also try inspecting current collection elements
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.fine("generic inspect failed for " + m.getName() + " : " + t.getMessage());
            }

            // If generic inspection didn't decide, inspect current collection contents (if any)
            try {
                Object col = m.invoke(premisRoot);
                if (col instanceof Collection) {
                    Collection<?> c = (Collection<?>) col;
                    if (!c.isEmpty()) {
                        Object sample = c.iterator().next();
                        if (sample != null && sample.getClass().isAssignableFrom(vClass)) {
                            ((Collection) col).add(value);
                            return true;
                        }
                        // also accept if value is assignable to sample's class
                        if (sample != null && vClass.isAssignableFrom(sample.getClass())) {
                            ((Collection) col).add(value);
                            return true;
                        }
                    } else {
                        // empty collection: we can still add (choose this if method name contains a hint)
                        String lname = m.getName().toLowerCase();
                        if (lname.contains("intellectual") || lname.contains("relationship") || lname.contains("agent") || lname.contains("object") || lname.contains("rights")) {
                            ((Collection) col).add(value);
                            return true;
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.fine("inspect/add via getter " + m.getName() + " failed: " + t.getMessage());
            }
        }

        // 2) Try direct fields (declared fields) with collection type and name hint
        for (Field f : premisRoot.getClass().getDeclaredFields()) {
            if (!Collection.class.isAssignableFrom(f.getType())) continue;
            String fname = f.getName().toLowerCase();
            if (!(fname.contains("intellectual") || fname.contains("relationship") || fname.contains("agent") || fname.contains("object") || fname.contains("rights"))) {
                continue;
            }
            f.setAccessible(true);
            Object val = f.get(premisRoot);
            if (val instanceof Collection) {
                ((Collection) val).add(value);
                return true;
            } else if (val == null) {
                List list = new ArrayList();
                list.add(value);
                f.set(premisRoot, list);
                return true;
            }
        }

        // 3) Fallback: try a generic getXxxx list for "Relationship" and "Intellectual"
        String[] fallbackNames = new String[] {"getRelationship","getIntellectualEntity","getIntellectualObject","getAgent","getRights","getObject"};
        for (String gn : fallbackNames) {
            try {
                Method gm = premisRoot.getClass().getMethod(gn);
                Object col = gm.invoke(premisRoot);
                if (col instanceof Collection) {
                    ((Collection) col).add(value);
                    return true;
                }
            } catch (NoSuchMethodException ns) {
                // ignore
            } catch (Throwable t) {
                LOG.fine("fallback getter attach failed: " + t.getMessage());
            }
        }
    } catch (Throwable t) {
        LOG.fine("attachToRootByValueType failed: " + t.getMessage());
    }

    return false;
}


    // ------------------- utilities for adding file objects -------------------

    /**
     * Create and add an <object> for a file using generated JAXB classes.
     * Returns the object instance (generated type).
     */
    private Object addFileObject(Path file, String relativePath, String formatName,
                                 boolean addCreatingApplication, String creatingApplicationName,
                                 boolean addReceivingDate) throws Exception {
        Object obj = createUsingFactoryOrUnmarshal(new String[]{"createObjectComplexType","createObjectType","createObject"}, gov.loc.premis.v3.ObjectComplexType.class, "object");
        if (obj == null) throw new IllegalStateException("Cannot create ObjectComplexType instance");

        // objectIdentifier
        Object oid = createUsingFactoryOrUnmarshal(new String[]{"createObjectIdentifierComplexType","createObjectIdentifier"}, gov.loc.premis.v3.ObjectIdentifierComplexType.class, "objectIdentifier");
        callSetterOrAdd(oid, "ObjectIdentifierType", createStringPlusAuthority("FilePath", null));
        callSetterOrAdd(oid, "ObjectIdentifierValue", relativePath);
        callGetterAndAdd(obj, "ObjectIdentifier", oid);

        // objectCharacteristics
        Object oc = createUsingFactoryOrUnmarshal(new String[]{"createObjectCharacteristicsComplexType","createObjectCharacteristics"}, gov.loc.premis.v3.ObjectCharacteristicsComplexType.class, "objectCharacteristics");
        Object comp = createUsingFactoryOrUnmarshal(new String[]{"createCompositionLevelComplexType"}, gov.loc.premis.v3.CompositionLevelComplexType.class, "compositionLevel");
        callSetterOrAdd(comp, "Value", "0");
        callSetterOrAdd(oc, "CompositionLevel", comp);

        // size BigInteger if available
        long size = 0L;
        try { size = Files.size(file); } catch (IOException ignored) {}
        try {
            Method m = findMethod(oc.getClass(), "setSize", BigInteger.class);
            if (m != null) m.invoke(oc, BigInteger.valueOf(size));
            else callSetterOrAdd(oc, "Size", BigInteger.valueOf(size));
        } catch (Throwable t) {
            callSetterOrAdd(oc, "Size", BigInteger.valueOf(size));
        }

        // fixity: algorithm + digest
        Object fix = createUsingFactoryOrUnmarshal(new String[]{"createFixityComplexType","createFixity"}, gov.loc.premis.v3.FixityComplexType.class, "fixity");
        if (!callSetterOrAdd(fix, "MessageDigestAlgorithm", "SHA-256")) {
            callSetterOrAdd(fix, "MessageDigestAlgorithm", createStringPlusAuthority("SHA-256", null));
        }
        callSetterOrAdd(fix, "MessageDigest", computeSha256(file));
        callGetterAndAdd(oc, "Fixity", fix);

        // format/designation/name
        Object fmt = createUsingFactoryOrUnmarshal(new String[]{"createFormatComplexType","createFormat"}, gov.loc.premis.v3.FormatComplexType.class, "format");
        Object fd = createUsingFactoryOrUnmarshal(new String[]{"createFormatDesignationComplexType","createFormatDesignation"}, gov.loc.premis.v3.FormatDesignationComplexType.class, "formatDesignation");
        callSetterOrAdd(fd, "FormatName", formatName);
        callGetterAndAdd(fmt, "FormatDesignation", fd);
        callGetterAndAdd(oc, "Format", fmt);

        // receivingDate extension (use DOM element or JAXBElement depending on generated api)
        Object ext = createUsingFactoryOrUnmarshal(new String[]{"createExtensionComplexType","createExtension"}, gov.loc.premis.v3.ExtensionComplexType.class, "extension");
        // try to add a DOM element to ext.any
        try {
            // create a simple javax.xml.bind.JAXBElement for receivingDate if getAny() expects Objects
            javax.xml.bind.JAXBElement<String> rd = new JAXBElement<>(new QName(PREMIS_NS, "receivingDate"), String.class, ISO.format(OffsetDateTime.now()));
            callGetterAndAdd(ext, "Any", rd);
        } catch (Throwable t) {
            // fallback: try to set ObjectCharacteristicsExtension as string field
            callSetterOrAdd(oc, "ObjectCharacteristicsExtension", ext);
        }
        callSetterOrAdd(oc, "ObjectCharacteristicsExtension", ext);
        callSetterOrAdd(obj, "ObjectCharacteristics", oc);

        // creatingApplication
        if (addCreatingApplication) {
            Object ca = createUsingFactoryOrUnmarshal(new String[]{"createCreatingApplicationComplexType","createCreatingApplication"}, gov.loc.premis.v3.CreatingApplicationComplexType.class, "creatingApplication");
            callSetterOrAdd(ca, "CreatingApplicationName", creatingApplicationName);
            callSetterOrAdd(ca, "DateCreatedByApplication", "2024-12-07T00:00:00+05:30");
            callSetterOrAdd(obj, "CreatingApplication", ca);
        }

        // attach object to root
        callGetterAndAdd(premisRoot, "Object", obj);
        LOG.info("Added object for: " + relativePath);
        return obj;
    }

    // ------------------- Marshalling -------------------


    private void marshal(Path outFile) throws Exception {
        Marshaller m = jaxb.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

        // try to set schemaLocation
        try {
            m.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, PREMIS_NS + " http://www.loc.gov/standards/premis/premis-3-0.xsd");
        } catch (Throwable ignored) {}

        // Try to set a NamespacePrefixMapper to force the "premis" prefix when using the JAXB RI (com.sun.xml.bind)
        try {
            // Avoid compile-time dependency on com.sun.* classes by setting via reflection
            Class<?> prefixMapperClass = null;
            try {
                prefixMapperClass = Class.forName("com.sun.xml.bind.marshaller.NamespacePrefixMapper");
            } catch (ClassNotFoundException cnf) {
                // not JAXB RI; try the alternative RI classname
                try { prefixMapperClass = Class.forName("com.sun.xml.internal.bind.marshaller.NamespacePrefixMapper"); } catch (Throwable t) { prefixMapperClass = null; }
            }
            if (prefixMapperClass != null) {
                final Object mapper = Proxy.newProxyInstance(
                    prefixMapperClass.getClassLoader(),
                    new Class<?>[]{ prefixMapperClass },
                    (proxy, method, args) -> {
                        String mname = method.getName();
                        if ("getPreferredPrefix".equals(mname) && args != null && args.length >= 3) {
                            String namespaceUri = (String) args[0];
                            // enforce the premis prefix for the PREMIS namespace
                            if (PREMIS_NS.equals(namespaceUri)) return "premis";
                            // keep default prefixes for common namespaces
                            if ("http://www.w3.org/2001/XMLSchema-instance".equals(namespaceUri)) return "xsi";
                            return (String) args[2]; // default
                        }
                        // default behaviour for other methods
                        return method.getDefaultValue();
                    }
                );
                // set via reflection: m.setProperty("com.sun.xml.bind.namespacePrefixMapper", mapper)
                try {
                    m.setProperty("com.sun.xml.bind.namespacePrefixMapper", mapper);
                } catch (Throwable t1) {
                    try { m.setProperty("com.sun.xml.internal.bind.namespacePrefixMapper", mapper); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LOG.fine("NamespacePrefixMapper not available: " + t.getMessage());
        }

        // Ensure root element uses the PREMIS namespace name "premis"
        JAXBElement<PremisComplexType> rootElem = this.premisElement;
        if (rootElem == null) {
            rootElem = new JAXBElement<>(new QName(PREMIS_NS, "premis"), PremisComplexType.class, premisRoot);
        }

        try (OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            m.marshal(rootElem, os);
        }
        LOG.info("Saved PREMIS to " + outFile.toAbsolutePath());
    }

    private void verifyAndLogRootContents() {
        try {
            String[] checks = new String[] {"IntellectualObject","IntellectualEntity","Agent","Rights","Object","Relationship","Event"};
            for (String name : checks) {
                Object val = null;
                try { val = callGetter(premisRoot, name); } catch (Throwable ignored) {}
                if (val == null) {
                    LOG.info("premis root: no " + name + " (getter returned null)");
                } else if (val instanceof Collection) {
                    LOG.info("premis root: " + name + " count = " + ((Collection) val).size());
                } else {
                    LOG.info("premis root: " + name + " present (single)");
                }
            }
        } catch (Throwable t) {
            LOG.fine("verifyAndLogRootContents error: " + t.getMessage());
        }
    }


    // ------------------- Reflection & helper utilities -------------------

    /**
     * Robust factory/unmarshal helper (tries factory methods, direct new, concrete impls, unmarshal).
     */
    @SuppressWarnings("unchecked")
    private <T> T createUsingFactoryOrUnmarshal(String[] candidateFactoryMethodNames, Class<T> clazz, String xmlLocalName) {
        // 1) try factory methods
        for (Method mtry : factory.getClass().getMethods()) {
            if (!mtry.getName().toLowerCase().startsWith("create")) continue;
            if (mtry.getParameterCount() != 0) continue;
            try {
                Object ret = mtry.invoke(factory);
                if (ret == null) continue;
                if (clazz.isInstance(ret)) return (T) ret;
                if (ret instanceof JAXBElement) {
                    Object val = ((JAXBElement<?>) ret).getValue();
                    if (val != null && clazz.isInstance(val)) return (T) val;
                }
            } catch (Throwable t) {
                LOG.fine("factory invocation failed: " + t.getMessage());
            }
        }
        // 2) explicit candidates
        for (String candidate : candidateFactoryMethodNames) {
            if (candidate == null) continue;
            try {
                Method m = factory.getClass().getMethod(candidate);
                Object r = m.invoke(factory);
                if (r == null) continue;
                if (clazz.isInstance(r)) return (T) r;
                if (r instanceof JAXBElement) {
                    Object v = ((JAXBElement<?>) r).getValue();
                    if (v != null && clazz.isInstance(v)) return (T) v;
                }
            } catch (NoSuchMethodException ns) {
                // ignore
            } catch (Throwable t) {
                LOG.fine("explicit factory " + candidate + " failed: " + t.getMessage());
            }
        }
        // 3) direct new
        try {
            T inst = clazz.getDeclaredConstructor().newInstance();
            return inst;
        } catch (Throwable t) {
            LOG.fine("direct new failed: " + t.getMessage());
        }
        // 4) try common concrete impl names (ClassNameJ / ClassNameImpl)
        try {
            String pkg = clazz.getPackage().getName();
            String base = clazz.getSimpleName();
            String[] suffixes = {"J", "Impl"};
            for (String sfx : suffixes) {
                String cn = pkg + "." + base + sfx;
                try {
                    Class<?> c = Class.forName(cn);
                    Object o = c.getDeclaredConstructor().newInstance();
                    if (clazz.isInstance(o)) return (T) o;
                } catch (ClassNotFoundException ignored) {}
            }
        } catch (Throwable t) {
            LOG.fine("concrete impl lookup failed: " + t.getMessage());
        }
        // 5) unmarshal fallback
        try {
            String local = (xmlLocalName != null && !xmlLocalName.isEmpty()) ? xmlLocalName : deriveLocalNameFromClass(clazz);
            String xml = "<" + local + " xmlns=\"" + PREMIS_NS + "\"/>";
            Unmarshaller u = jaxb.createUnmarshaller();
            StreamSource src = new StreamSource(new StringReader(xml));
            JAXBElement<T> je = u.unmarshal(src, clazz);
            T val = je.getValue();
            if (val != null) return val;
        } catch (Throwable t) {
            LOG.fine("unmarshal fallback failed: " + t.getMessage());
        }
        LOG.warning("Unable to create instance of " + clazz.getName());
        return null;
    }

    private String deriveLocalNameFromClass(Class<?> clazz) {
        String s = clazz.getSimpleName().replaceAll("ComplexType$", "").replaceAll("Type$", "");
        if (s.length() > 0) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        return s.toLowerCase();
    }

    /** Create a gov.loc.premis.v3.StringPlusAuthority populated value, using factory if present. */
    private gov.loc.premis.v3.StringPlusAuthority createStringPlusAuthority(String value, String authority) {
        try {
            Method m = factory.getClass().getMethod("createStringPlusAuthority");
            Object spa = m.invoke(factory);
            if (spa instanceof gov.loc.premis.v3.StringPlusAuthority) {
                gov.loc.premis.v3.StringPlusAuthority s = (gov.loc.premis.v3.StringPlusAuthority) spa;
                s.setValue(value);
                if (authority != null) s.setAuthority(authority);
                return s;
            }
        } catch (Exception ignored) {}
        gov.loc.premis.v3.StringPlusAuthority s = new gov.loc.premis.v3.StringPlusAuthority();
        s.setValue(value);
        if (authority != null) s.setAuthority(authority);
        return s;
    }

    // call setter or add to getter list
    private boolean callSetterOrAdd(Object target, String propName, Object value) {
        if (target == null) return false;
        String setter = "set" + propName;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equalsIgnoreCase(setter) && m.getParameterCount() == 1) {
                    Class<?> param = m.getParameterTypes()[0];
                    Object arg = adaptArgumentForParameter(value, param);
                    m.invoke(target, arg);
                    return true;
                }
            }
            // fallback to getter list
            String getter = "get" + propName;
            Method gm = findMethod(target.getClass(), getter);
            if (gm != null) {
                Object got = gm.invoke(target);
                if (got instanceof Collection) {
                    ((Collection) got).add(value);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.fine("callSetterOrAdd failed for " + propName + ": " + e.getMessage());
        }
        return false;
    }

    private boolean callGetterAndAdd(Object target, String getterShort, Object valueToAdd) {
        try {
            Method gm = findMethod(target.getClass(), "get" + getterShort);
            if (gm == null) {
                for (Method m : target.getClass().getMethods()) {
                    if (m.getName().toLowerCase().startsWith("get") && Collection.class.isAssignableFrom(m.getReturnType())
                            && m.getName().toLowerCase().contains(getterShort.toLowerCase())) {
                        gm = m;
                        break;
                    }
                }
            }
            if (gm != null) {
                Object list = gm.invoke(target);
                if (list instanceof Collection) {
                    ((Collection) list).add(valueToAdd);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.fine("callGetterAndAdd failed for " + getterShort + ": " + e.getMessage());
        }
        return false;
    }

    private Object callGetter(Object target, String getterShort) {
        if (target == null) return null;
        try {
            Method gm = findMethod(target.getClass(), "get" + getterShort);
            if (gm != null) return gm.invoke(target);
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().toLowerCase().startsWith("get") && m.getName().toLowerCase().contains(getterShort.toLowerCase())) {
                    return m.invoke(target);
                }
            }
        } catch (Exception e) {
            LOG.fine("callGetter failed for " + getterShort + ": " + e.getMessage());
        }
        return null;
    }

    private Method findMethod(Class<?> c, String name, Class<?>... paramTypes) {
        if (c == null || name == null) return null;
        try {
            return c.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            try {
                Method dm = c.getDeclaredMethod(name, paramTypes);
                dm.setAccessible(true);
                return dm;
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }

    private Method findMethod(Class<?> c, String name) {
        if (c == null || name == null) return null;
        for (Method m : c.getMethods()) {
            if (m.getName().equalsIgnoreCase(name)) return m;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equalsIgnoreCase(name)) { m.setAccessible(true); return m; }
        }
        return null;
    }

    private Object adaptArgumentForParameter(Object value, Class<?> param) {
        if (value == null) return null;
        if (param.isAssignableFrom(value.getClass())) return value;
        if (param == BigInteger.class) {
            if (value instanceof Number) return BigInteger.valueOf(((Number) value).longValue());
            try { return new BigInteger(value.toString()); } catch (Exception ignored) {}
        }
        if (param == String.class) return value.toString();
        if (value instanceof JAXBElement) {
            Object v = ((JAXBElement<?>) value).getValue();
            if (param.isAssignableFrom(v.getClass())) return v;
        }
        return value;
    }

    private List<Path> filterByExt(List<Path> files, String ext) {
        List<Path> out = new ArrayList<>();
        for (Path p : files) if (p.toString().toLowerCase().endsWith(ext.toLowerCase())) out.add(p);
        return out;
    }

    private Path findFirstWhere(List<Path> list, java.util.function.Predicate<Path> pred) {
        for (Path p : list) if (pred.test(p)) return p;
        return null;
    }

    private List<Path> findAllUnder(List<Path> all, Path dir) {
        List<Path> out = new ArrayList<>();
        String frag = dir.toString().replace('\\','/').toLowerCase();
        for (Path p : all) if (p.toString().replace('\\','/').toLowerCase().contains(frag)) out.add(p);
        return out;
    }

    private Path findFirstUnderExtension(List<Path> all, String ext) {
        for (Path p : all) if (p.toString().toLowerCase().endsWith(ext.toLowerCase())) return p;
        return null;
    }

    private String computeSha256(Path f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(f)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) md.update(buf, 0, r);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            LOG.fine("computeSha256 failed: " + e.getMessage());
            return "";
        }
    }

    // ------------------- CLI -------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PremisCombinedGenerator <sip-root> [<out-file>]");
            System.exit(2);
        }
        Path sip = Paths.get(args[0]);
        Path out = args.length >= 2 ? Paths.get(args[1]) : sip.resolve("odhc_premis_combined.xml");
        PremisCombinedGenerator gen = new PremisCombinedGenerator(sip);
        gen.generateAndSave(out);
    }
}
