package com.example;

import gov.loc.premis.v3.ObjectFactory;
import gov.loc.premis.v3.PremisComplexType;

import javax.xml.bind.*;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.XMLConstants;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * PremisJaxbV3Generator
 *
 * Generates/updates PREMIS v3 XML for a SIP folder.
 *
 * - Uses gov.loc.premis.v3 generated JAXB classes.
 * - Robust to variation in codegen (uses factory when present, reflection and XML-unmarshal fallback).
 */
public class PremisJaxbV3Generator {
    private static final Logger LOG = Logger.getLogger(PremisJaxbV3Generator.class.getName());
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String PREMIS_NS = "http://www.loc.gov/premis/v3";
    private final Path sipRoot;
    private final JAXBContext jaxb;
    private final ObjectFactory factory;
    private PremisComplexType premisRoot; // strong type for convenience
    private JAXBElement<PremisComplexType> premisElement;

    public PremisJaxbV3Generator(Path sipRoot) throws Exception {
        if (sipRoot == null || !Files.isDirectory(sipRoot)) {
            throw new IllegalArgumentException("sipRoot must be an existing directory");
        }
        this.sipRoot = sipRoot;
        this.jaxb = JAXBContext.newInstance("gov.loc.premis.v3");
        this.factory = new ObjectFactory();

        // create or load premis root
        createOrLoadPremisRoot();

        // Optional<Path> existing = findExistingPremisFile();
        // if (existing.isPresent()) {
        //     unmarshalExisting(existing.get());
        //     LOG.info("Loaded existing PREMIS from " + existing.get());
        // } else {
        //     initializeBasicPremis();
        //     LOG.info("Created new PREMIS root");
        // }
    }

    private void createOrLoadPremisRoot() {
        // Try factory methods
        try {
            // some generated ObjectFactory#createPremis() returns JAXBElement<PremisComplexType>
            try {
                Method m = ObjectFactory.class.getMethod("createPremis");
                Object ret = m.invoke(factory);
                if (ret instanceof JAXBElement) {
                    @SuppressWarnings("unchecked")
                    JAXBElement<PremisComplexType> je = (JAXBElement<PremisComplexType>) ret;
                    this.premisElement = je;
                    this.premisRoot = je.getValue();
                    return;
                }
            } catch (NoSuchMethodException ignored) { /* ignore */ }

            // some factories have createPremis(PremisComplexType)
            try {
                Method m2 = ObjectFactory.class.getMethod("createPremis", PremisComplexType.class);
                PremisComplexType tmp = new PremisComplexType();
                @SuppressWarnings("unchecked")
                JAXBElement<PremisComplexType> je2 = (JAXBElement<PremisComplexType>) m2.invoke(factory, tmp);
                this.premisElement = je2;
                this.premisRoot = je2.getValue();
                return;
            } catch (NoSuchMethodException ignored) { /* ignore */ }
        } catch (Throwable t) {
            LOG.fine("createOrLoadPremisRoot factory attempts failed: " + t.getMessage());
        }

        // fallback: create root and wrap
        this.premisRoot = new PremisComplexType();
        this.premisElement = new JAXBElement<>(new QName(PREMIS_NS, "premis"), PremisComplexType.class, this.premisRoot);
    }

    private Optional<Path> findExistingPremisFile() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sipRoot, "*.xml")) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.contains("premis")) return Optional.of(p);
            }
        }
        final Path[] found = {null};
        Files.walkFileTree(sipRoot, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString().toLowerCase();
                if (n.endsWith(".xml") && n.contains("premis")) {
                    found[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    private void unmarshalExisting(Path xml) throws Exception {
        Unmarshaller u = jaxb.createUnmarshaller();
        try (InputStream is = Files.newInputStream(xml)) {
            Object unmar = u.unmarshal(is);
            if (unmar instanceof JAXBElement) {
                @SuppressWarnings("unchecked")
                JAXBElement<PremisComplexType> je = (JAXBElement<PremisComplexType>) unmar;
                this.premisElement = je;
                this.premisRoot = je.getValue();
            } else if (unmar instanceof PremisComplexType) {
                this.premisRoot = (PremisComplexType) unmar;
                this.premisElement = new JAXBElement<>(new QName(PREMIS_NS, "premis"), PremisComplexType.class, this.premisRoot);
            }
        }
    }

    /**
     * Create a minimal intellectualEntity, two agents and rights and attach to root.
     */
    private void initializeBasicPremis() {
        try {
            // intellectualEntity (PREMIS v3)
            Object ie = createUsingFactoryOrUnmarshal(new String[] {"createIntellectualEntity","createIntellectualEntityComplexType"}, gov.loc.premis.v3.IntellectualEntity.class, "intellectualEntity");

            // objectIdentifier under intellectualEntity
            Object oid = createUsingFactoryOrUnmarshal(new String[] {"createObjectIdentifierComplexType","createObjectIdentifier"}, gov.loc.premis.v3.ObjectIdentifierComplexType.class, "objectIdentifier");
            callSetterOrAdd(oid, "ObjectIdentifierType", createStringPlusAuthority("SIP-ID", null));
            callSetterOrAdd(oid, "ObjectIdentifierValue", sipRoot.getFileName().toString());
            callGetterAndAdd(ie, "ObjectIdentifier", oid);

            Object oc = createUsingFactoryOrUnmarshal(new String[] {"createObjectCharacteristicsComplexType","createObjectCharacteristics"}, gov.loc.premis.v3.ObjectCharacteristicsComplexType.class, "objectCharacteristics");
            Object comp = createUsingFactoryOrUnmarshal(new String[] {"createCompositionLevelComplexType"}, gov.loc.premis.v3.CompositionLevelComplexType.class, "compositionLevel");
            callSetterOrAdd(comp, "Value", "1");
            callSetterOrAdd(oc, "CompositionLevel", comp);
            callSetterOrAdd(ie, "ObjectCharacteristics", oc);

            callGetterAndAdd(ie, "SignificantProperties", "Case SIP: metadata + rep1 + rep2 + schema");

            // attach intellectual entity to premis root
            callGetterAndAdd(premisRoot, "IntellectualEntity", ie);

        } catch (Throwable t) {
            LOG.warning("initializeBasicPremis intellectualEntity creation problem: " + t.getMessage());
        }

        // Agents
        try {
            Object systemAgent = createUsingFactoryOrUnmarshal(new String[] {"createAgentComplexType"}, gov.loc.premis.v3.AgentComplexType.class, "agent");
            Object aid = createUsingFactoryOrUnmarshal(new String[] {"createAgentIdentifierComplexType"}, gov.loc.premis.v3.AgentIdentifierComplexType.class, "agentIdentifier");
            callSetterOrAdd(aid, "AgentIdentifierType", createStringPlusAuthority("system", null));
            callSetterOrAdd(aid, "AgentIdentifierValue", "JDPS-Repository");
            callGetterAndAdd(systemAgent, "AgentIdentifier", aid);
            callGetterAndAdd(systemAgent, "AgentName", "JDPS Preservation System");
            callSetterOrAdd(systemAgent, "AgentType", "software");
            callGetterAndAdd(premisRoot, "Agent", systemAgent);

            Object uploader = createUsingFactoryOrUnmarshal(new String[] {"createAgentComplexType"}, gov.loc.premis.v3.AgentComplexType.class, "agent");
            Object uaid = createUsingFactoryOrUnmarshal(new String[] {"createAgentIdentifierComplexType"}, gov.loc.premis.v3.AgentIdentifierComplexType.class, "agentIdentifier");
            callSetterOrAdd(uaid, "AgentIdentifierType", createStringPlusAuthority("person", null));
            callSetterOrAdd(uaid, "AgentIdentifierValue", "uploader@example.org");
            callGetterAndAdd(uploader, "AgentIdentifier", uaid);
            callGetterAndAdd(uploader, "AgentName", "Case Uploader");
            callSetterOrAdd(uploader, "AgentType", "human");
            callGetterAndAdd(premisRoot, "Agent", uploader);
        } catch (Throwable t) {
            LOG.warning("initializeBasicPremis agents creation problem: " + t.getMessage());
        }

        // Rights (simple)
        try {
            Object rights = createUsingFactoryOrUnmarshal(new String[] {"createRightsComplexType"}, gov.loc.premis.v3.RightsComplexType.class, "rights");
            Object rsc = createUsingFactoryOrUnmarshal(new String[] {"createRightsStatementComplexType"}, gov.loc.premis.v3.RightsStatementComplexType.class, "rightsStatement");
            // rightsBasis may be StringPlusAuthority or String
            if (!callSetterOrAdd(rsc, "RightsBasis", "statute")) {
                callSetterOrAdd(rsc, "RightsBasis", createStringPlusAuthority("statute", null));
            }
            callSetterOrAdd(rsc, "RightsGranted", "Access restricted to authorized court staff");
            callGetterAndAdd(rights, "RightsStatement", rsc);
            callGetterAndAdd(premisRoot, "Rights", rights);
        } catch (Throwable t) {
            LOG.warning("initializeBasicPremis rights creation problem: " + t.getMessage());
        }
    }

    /**
     * Scans SIP folder and ensures objects for pdf/xml/xsd files
     */
    public void scanAndEnsureObjects() throws Exception {
        LOG.info("Scanning SIP tree: " + sipRoot.toAbsolutePath());
        Files.walkFileTree(sipRoot, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".pdf") || name.endsWith(".xml") || name.endsWith(".xsd")) {
                        if (name.contains("premis")) return FileVisitResult.CONTINUE;
                        ensureObjectForFile(file);
                    }
                } catch (Exception ex) {
                    LOG.warning("scan error: " + ex.getMessage());
                    ex.printStackTrace();
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Ensure object entry exists for a file; create/update size+fixity+format.
     */
    @SuppressWarnings("unchecked")
    public void ensureObjectForFile(Path file) throws Exception {
        String rel = sipRoot.relativize(file).toString().replace(File.separatorChar, '/');

        // find existing objects
        List<Object> existingObjects = getListFromPremisRoot("Object");
        for (Object o : existingObjects) {
            List<Object> oids = (List<Object>) callGetter(o, "ObjectIdentifier");
            if (oids != null) {
                for (Object oid : oids) {
                    Object val = callGetter(oid, "ObjectIdentifierValue");
                    if (val != null && rel.equals(val.toString())) {
                        // update
                        updateSizeAndFixity(o, file);
                        LOG.info("Updated existing PREMIS object for " + rel);
                        return;
                    }
                }
            }
        }

        // create new ObjectComplexType
        Object obj = createUsingFactoryOrUnmarshal(new String[] {"createObjectComplexType","createObjectType","createObject"}, gov.loc.premis.v3.ObjectComplexType.class, "object");
        if (obj == null) throw new IllegalStateException("Unable to create ObjectComplexType instance");

        // objectIdentifier
        Object oid = createUsingFactoryOrUnmarshal(new String[] {"createObjectIdentifierComplexType","createObjectIdentifier"}, gov.loc.premis.v3.ObjectIdentifierComplexType.class, "objectIdentifier");
        callSetterOrAdd(oid, "ObjectIdentifierType", createStringPlusAuthority("FilePath", null));
        callSetterOrAdd(oid, "ObjectIdentifierValue", rel);
        callGetterAndAdd(obj, "ObjectIdentifier", oid);

        // objectCharacteristics
        Object oc = createUsingFactoryOrUnmarshal(new String[] {"createObjectCharacteristicsComplexType","createObjectCharacteristics"}, gov.loc.premis.v3.ObjectCharacteristicsComplexType.class, "objectCharacteristics");
        Object comp = createUsingFactoryOrUnmarshal(new String[] {"createCompositionLevelComplexType"}, gov.loc.premis.v3.CompositionLevelComplexType.class, "compositionLevel");
        callSetterOrAdd(comp, "Value", "0");
        callSetterOrAdd(oc, "CompositionLevel", comp);

        // size as BigInteger when possible
        long size = Files.size(file);
        boolean sizeSet = false;
        try {
            Method m = findMethod(oc.getClass(), "setSize", BigInteger.class);
            if (m != null) {
                m.invoke(oc, BigInteger.valueOf(size));
                sizeSet = true;
            }
        } catch (Throwable t) { LOG.fine("setSize(BigInteger) attempt failed: " + t.getMessage()); }
        if (!sizeSet) {
            callSetterOrAdd(oc, "Size", BigInteger.valueOf(size));
        }

        // fixity
        Object fix = createUsingFactoryOrUnmarshal(new String[] {"createFixityComplexType","createFixity"}, gov.loc.premis.v3.FixityComplexType.class, "fixity");
        // messageDigestAlgorithm may be a String or StringPlusAuthority
        if (!callSetterOrAdd(fix, "MessageDigestAlgorithm", "SHA-256")) {
            callSetterOrAdd(fix, "MessageDigestAlgorithm", createStringPlusAuthority("SHA-256", null));
        }
        callSetterOrAdd(fix, "MessageDigest", computeSha256(file));
        callGetterAndAdd(oc, "Fixity", fix);

        // format + designation + name
        Object fmt = createUsingFactoryOrUnmarshal(new String[] {"createFormatComplexType","createFormat"}, gov.loc.premis.v3.FormatComplexType.class, "format");
        Object fd = createUsingFactoryOrUnmarshal(new String[] {"createFormatDesignationComplexType","createFormatDesignation"}, gov.loc.premis.v3.FormatDesignationComplexType.class, "formatDesignation");
        callSetterOrAdd(fd, "FormatName", detectFormatName(file));
        callGetterAndAdd(fmt, "FormatDesignation", fd);
        callGetterAndAdd(oc, "Format", fmt);

        // objectCharacteristicsExtension -> receivingDate (DOM Element)
        Object ext = createUsingFactoryOrUnmarshal(new String[] {"createExtensionComplexType","createExtension"}, gov.loc.premis.v3.ExtensionComplexType.class, "extension");
        try {
            // create DOM element <receivingDate>...</receivingDate>
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();
            Element rd = doc.createElementNS(PREMIS_NS, "receivingDate");
            rd.setTextContent(ISO.format(OffsetDateTime.now()));
            callGetterAndAdd(ext, "Any", rd);
            callSetterOrAdd(oc, "ObjectCharacteristicsExtension", ext);
        } catch (Exception e) {
            LOG.fine("creating DOM receivingDate failed: " + e.getMessage());
        }

        callSetterOrAdd(obj, "ObjectCharacteristics", oc);

        // creatingApplication
        Object ca = createUsingFactoryOrUnmarshal(new String[] {"createCreatingApplicationComplexType","createCreatingApplication"}, gov.loc.premis.v3.CreatingApplicationComplexType.class, "creatingApplication");
        callSetterOrAdd(ca, "CreatingApplicationName", "JDPS-Repository");
        callSetterOrAdd(ca, "DateCreatedByApplication", fileTimeISO(file));
        callSetterOrAdd(obj, "CreatingApplication", ca);

        // attach object to root
        callGetterAndAdd(premisRoot, "Object", obj);
        LOG.info("Added PREMIS object for " + rel);
    }

    private void updateSizeAndFixity(Object obj, Path file) throws Exception {
        Object oc = callGetter(obj, "ObjectCharacteristics");
        if (oc == null) {
            oc = createUsingFactoryOrUnmarshal(new String[] {"createObjectCharacteristicsComplexType"}, gov.loc.premis.v3.ObjectCharacteristicsComplexType.class, "objectCharacteristics");
            callSetterOrAdd(obj, "ObjectCharacteristics", oc);
        }

        long size = Files.size(file);
        try {
            Method m = findMethod(oc.getClass(), "setSize", BigInteger.class);
            if (m != null) m.invoke(oc, BigInteger.valueOf(size));
            else callSetterOrAdd(oc, "Size", BigInteger.valueOf(size));
        } catch (Throwable t) {
            LOG.fine("updateSizeAndFixity setSize failed: " + t.getMessage());
            callSetterOrAdd(oc, "Size", BigInteger.valueOf(size));
        }

        Object fix = createUsingFactoryOrUnmarshal(new String[] {"createFixityComplexType","createFixity"}, gov.loc.premis.v3.FixityComplexType.class, "fixity");
        callSetterOrAdd(fix, "MessageDigestAlgorithm", "SHA-256");
        callSetterOrAdd(fix, "MessageDigest", computeSha256(file));

        // replace fixity list or set single
        Object fixList = callGetter(oc, "Fixity");
        if (fixList instanceof Collection) {
            ((Collection) fixList).clear();
            ((Collection) fixList).add(fix);
        } else {
            callSetterOrAdd(oc, "Fixity", fix);
        }

        Object ca = callGetter(obj, "CreatingApplication");
        if (ca == null) {
            ca = createUsingFactoryOrUnmarshal(new String[] {"createCreatingApplicationComplexType"}, gov.loc.premis.v3.CreatingApplicationComplexType.class, "creatingApplication");
            callSetterOrAdd(obj, "CreatingApplication", ca);
        }
        callSetterOrAdd(ca, "DateCreatedByApplication", fileTimeISO(file));
    }

    public void addIngestEvent(String detail) {
        try {
            Object ev = createUsingFactoryOrUnmarshal(new String[] {"createEventComplexType","createEvent"}, gov.loc.premis.v3.EventComplexType.class, "event");
            Object eid = createUsingFactoryOrUnmarshal(new String[] {"createEventIdentifierComplexType"}, gov.loc.premis.v3.EventIdentifierComplexType.class, "eventIdentifier");
            callSetterOrAdd(eid, "EventIdentifierType", "eventID");
            callSetterOrAdd(eid, "EventIdentifierValue", "EVT-INGEST-" + System.currentTimeMillis());
            callGetterAndAdd(ev, "EventIdentifier", eid);
            callSetterOrAdd(ev, "EventType", "ingest");
            callSetterOrAdd(ev, "EventDateTime", ISO.format(OffsetDateTime.now()));
            Object edi = createUsingFactoryOrUnmarshal(new String[] {"createEventDetailInformationComplexType"}, gov.loc.premis.v3.EventDetailInformationComplexType.class, "eventDetailInformation");
            callSetterOrAdd(edi, "EventDetail", detail);
            callSetterOrAdd(ev, "EventDetailInformation", edi);
            Object eoi = createUsingFactoryOrUnmarshal(new String[] {"createEventOutcomeInformationComplexType"}, gov.loc.premis.v3.EventOutcomeInformationComplexType.class, "eventOutcomeInformation");
            callGetterAndAdd(eoi, "EventOutcome", "success");
            callSetterOrAdd(ev, "EventOutcomeInformation", eoi);

            Object la = createUsingFactoryOrUnmarshal(new String[] {"createLinkingAgentIdentifierComplexType"}, gov.loc.premis.v3.LinkingAgentIdentifierComplexType.class, "linkingAgentIdentifier");
            callSetterOrAdd(la, "LinkingAgentIdentifierType", createStringPlusAuthority("system", null));
            callSetterOrAdd(la, "LinkingAgentIdentifierValue", "JDPS-Repository");
            callGetterAndAdd(ev, "LinkingAgentIdentifier", la);

            callGetterAndAdd(premisRoot, "Event", ev);

            LOG.info("Added ingest event");
        } catch (Throwable t) {
            LOG.warning("addIngestEvent failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Save premisRoot to disk
     */
    public void save(Path outFile) throws Exception {
        Marshaller m = jaxb.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        // set schemaLocation for PREMIS v3
        try {
            m.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, PREMIS_NS + " http://www.loc.gov/standards/premis/premis-3-0.xsd");
        } catch (Exception e) {
            LOG.fine("Could not set schemaLocation property: " + e.getMessage());
        }
        try (OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            m.marshal(premisElement, os);
        }
        LOG.info("Saved PREMIS to " + outFile);

        // optional: validate the output against provided premis.xsd if available in working dir
        try {
            Path schemaPath = Paths.get("premis.xsd");
            if (Files.exists(schemaPath)) {
                SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                Schema schema = sf.newSchema(schemaPath.toFile());
                Validator v = schema.newValidator();
                v.validate(new StreamSource(outFile.toFile()));
                LOG.info("Validation successful against premis.xsd");
            }
        } catch (Exception ex) {
            LOG.warning("Validation failed or premis.xsd not present: " + ex.getMessage());
        }
    }

    // -------------------- Reflection & factory helpers --------------------

    /**
     * Robust creation helper. Attempts:
     *  - any createXxx() method on ObjectFactory that returns required type (or JAXBElement of it)
     *  - explicit candidate factory names (if supplied)
     *  - direct no-arg constructor
     *  - try to find a concrete impl (ClassNameJ or ClassNameImpl) in same package and instantiate
     *  - unmarshal minimal XML fragment
     */
    @SuppressWarnings("unchecked")
    private <T> T createUsingFactoryOrUnmarshal(String[] candidateFactoryMethodNames, Class<T> clazz, String xmlLocalName) {
        // 1) try factory methods generically
        for (Method mtry : factory.getClass().getMethods()) {
            if (!mtry.getName().toLowerCase().startsWith("create")) continue;
            if (mtry.getParameterCount() != 0) continue;
            try {
                Object ret = mtry.invoke(factory);
                if (ret == null) continue;
                if (clazz.isInstance(ret)) {
                    LOG.info("Factory method " + mtry.getName() + " returned instance of " + clazz.getSimpleName());
                    return (T) ret;
                }
                if (ret instanceof JAXBElement) {
                    Object val = ((JAXBElement<?>) ret).getValue();
                    if (val != null && clazz.isInstance(val)) {
                        LOG.info("Factory method " + mtry.getName() + " returned JAXBElement of " + val.getClass().getSimpleName());
                        return (T) val;
                    }
                }
            } catch (Throwable t) {
                LOG.fine("Factory method " + mtry.getName() + " invocation failed: " + t.getMessage());
            }
        }

        // 2) explicit candidate names
        for (String candidate : candidateFactoryMethodNames) {
            if (candidate == null || candidate.isEmpty()) continue;
            try {
                Method m = factory.getClass().getMethod(candidate);
                Object r = m.invoke(factory);
                if (r == null) continue;
                if (clazz.isInstance(r)) {
                    LOG.info("Factory explicit method " + candidate + " produced instance");
                    return (T) r;
                }
                if (r instanceof JAXBElement) {
                    Object v = ((JAXBElement<?>) r).getValue();
                    if (v != null && clazz.isInstance(v)) {
                        LOG.info("Factory explicit method " + candidate + " returned JAXBElement value of expected type");
                        return (T) v;
                    }
                }
            } catch (NoSuchMethodException ns) {
                // ignore
            } catch (Throwable t) {
                LOG.fine("explicit factory method " + candidate + " invocation failed: " + t.getMessage());
            }
        }

        // 3) direct new
        try {
            T inst = clazz.getDeclaredConstructor().newInstance();
            LOG.info("Instantiated " + clazz.getSimpleName() + " via direct constructor");
            return inst;
        } catch (Throwable t) {
            LOG.fine("Direct instantiation of " + clazz.getSimpleName() + " failed: " + t.getMessage());
        }

        // 4) try to instantiate a concrete impl class with common suffixes (J / Impl)
        try {
            String pkg = clazz.getPackage().getName();
            String base = clazz.getSimpleName();
            String[] suffixes = new String[] {"J", "Impl"};
            for (String sfx : suffixes) {
                String candidateName = pkg + "." + base + sfx;
                try {
                    Class<?> concrete = Class.forName(candidateName);
                    Object obj = concrete.getDeclaredConstructor().newInstance();
                    if (clazz.isInstance(obj)) {
                        LOG.info("Instantiated concrete implementation " + candidateName);
                        return (T) obj;
                    }
                } catch (ClassNotFoundException cnf) {
                    // try next
                }
            }
        } catch (Throwable t) {
            LOG.fine("Concrete impl search failed: " + t.getMessage());
        }

        // 5) unmarshal fallback
        try {
            String local = (xmlLocalName != null && !xmlLocalName.isEmpty()) ? xmlLocalName : deriveLocalNameFromClass(clazz);
            String xml = "<" + local + " xmlns=\"" + PREMIS_NS + "\"/>";

            Unmarshaller u = jaxb.createUnmarshaller();
            StreamSource source = new StreamSource(new StringReader(xml));
            try {
                JAXBElement<T> je = u.unmarshal(source, clazz);
                T instance = je.getValue();
                if (instance != null) {
                    LOG.info("Unmarshal fallback produced instance of " + clazz.getSimpleName());
                    return instance;
                }
            } catch (Throwable ex) {
                LOG.fine("unmarshal(Source, Class) failed: " + ex.getMessage());
                // try older unmarshal
                Object o = u.unmarshal(new StringReader(xml));
                if (o instanceof JAXBElement) {
                    Object val = ((JAXBElement<?>) o).getValue();
                    if (val != null && clazz.isInstance(val)) {
                        LOG.info("Unmarshal (StringReader) produced JAXBElement with correct value type for " + clazz.getSimpleName());
                        return (T) val;
                    }
                }
            }
        } catch (Throwable e) {
            LOG.fine("Unmarshal fallback failed for " + clazz.getName() + ": " + e.getMessage());
        }

        LOG.warning("Unable to create an instance of " + clazz.getName());
        return null;
    }

    private String deriveLocalNameFromClass(Class<?> clazz) {
        String simple = clazz.getSimpleName();
        simple = simple.replaceAll("ComplexType$", "").replaceAll("Type$", "");
        if (simple.length() > 0) return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return simple.toLowerCase();
    }

    /** create StringPlusAuthority using factory or direct new */
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
        } catch (Exception ignored) { }
        gov.loc.premis.v3.StringPlusAuthority s = new gov.loc.premis.v3.StringPlusAuthority();
        s.setValue(value);
        if (authority != null) s.setAuthority(authority);
        return s;
    }

    // call setter or add to getter list if appropriate
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
            // fallback to getter that returns a collection
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

    // call getter and add to list returned
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

    // find method by exact param types
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

    // find any method by name (case-insensitive)
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

    private List<Object> getListFromPremisRoot(String propName) {
        Object got = callGetter(premisRoot, propName);
        if (got instanceof Collection) return new ArrayList<>((Collection<?>) got);
        if (got != null) return Collections.singletonList(got);
        return Collections.emptyList();
    }

    private String computeSha256(Path f) throws Exception {
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
    }

    private String detectFormatName(Path f) {
        String fn = f.getFileName().toString().toLowerCase();
        if (fn.endsWith(".pdf")) {
            String path = f.toString().toLowerCase();
            if (path.contains(FileSystems.getDefault().getSeparator() + "rep2" + FileSystems.getDefault().getSeparator()) || path.contains("/rep2/")) return "PDF/A-1B";
            return "PDF";
        } else if (fn.endsWith(".xml")) return "XML";
        else if (fn.endsWith(".xsd")) return "XSD";
        else return fn.substring(fn.lastIndexOf('.')+1).toUpperCase();
    }

    private String fileTimeISO(Path f) {
        try {
            FileTime ft = Files.getLastModifiedTime(f);
            return ISO.format(ft.toInstant().atOffset(OffsetDateTime.now().getOffset()));
        } catch (Exception ex) {
            return ISO.format(OffsetDateTime.now());
        }
    }

    // ----------------- main for CLI -----------------
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PremisJaxbV3Generator <sip-root> [out-premis.xml]");
            System.exit(2);
        }
        Path sip = Paths.get(args[0]);
        Path out = args.length >= 2 ? Paths.get(args[1]) : sip.resolve("odhc_premis_v3.xml");
        PremisJaxbV3Generator g = new PremisJaxbV3Generator(sip);
        // g.scanAndEnsureObjects();
        // g.addIngestEvent("SIP ingested by PremisJaxbV3Generator");
        g.save(out);
    }
}
