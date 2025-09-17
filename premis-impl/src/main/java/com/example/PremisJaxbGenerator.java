// package com.example;

// import gov.loc.premis.v3.*;
// import javax.xml.bind.*;
// import javax.xml.namespace.QName;
// import java.io.*;
// import java.nio.file.*;
// import java.nio.file.attribute.BasicFileAttributes;
// import java.security.*;
// import java.time.*;
// import java.time.format.DateTimeFormatter;
// import java.util.*;
// import java.util.logging.Logger;

// /**
//  * PremisJaxbGenerator (strongly-typed JAXB version)
//  *
//  * Requires generated JAXB classes for PREMIS 3.0 under package:
//  *   com.example.premis.gov.loc.premis.v3
//  *
//  * Usage:
//  *   Path sip = Paths.get("/path/to/ODHC010879122024");
//  *   PremisJaxbGenerator g = new PremisJaxbGenerator(sip);
//  *   g.scanAndEnsureObjects();
//  *   g.addIngestEvent("SIP ingested by pipeline");
//  *   g.save(sip.resolve("odhc_premis_jaxb.xml"));
//  *
//  * IMPORTANT:
//  * If any generated class name in your project differs from the types used below,
//  * update the import and the references (marked with TODO comments).
//  */
// public class PremisJaxbGenerator {
//     private static final Logger LOG = Logger.getLogger(PremisJaxbGenerator.class.getName());
//     private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

//     private final Path sipRoot;
//     private final JAXBContext jaxb;
//     private final ObjectFactory factory;
//     private Premis premisRoot;           // generated root type (Premis)
//     private JAXBElement<Premis> premisElement;

//     public PremisJaxbGenerator(Path sipRoot) throws Exception {
//         if (sipRoot == null || !Files.isDirectory(sipRoot)) {
//             throw new IllegalArgumentException("sipRoot must be an existing directory");
//         }
//         this.sipRoot = sipRoot;
//         // initialize JAXB context for the generated package
//         this.jaxb = JAXBContext.newInstance("com.example.premis.gov.loc.premis.v3"); // <- update if package differs

//         // create ObjectFactory and root Premis element
//         this.factory = new ObjectFactory();

//         // try to create a Premis instance via ObjectFactory. Typical method: createPremis()
//         // If your ObjectFactory has another method name, change below accordingly.
//         try {
//             // Many generators have ObjectFactory#createPremis() returning JAXBElement<Premis>
//             java.lang.reflect.Method m = factory.getClass().getMethod("createPremis", Premis.class);
//             // if present, create empty Premis and wrap
//             this.premisRoot = new Premis();
//             this.premisElement = (JAXBElement<Premis>) m.invoke(factory, this.premisRoot);
//         } catch (NoSuchMethodException nsme) {
//             // fallback: attempt ObjectFactory#createPremis() with no args that returns JAXBElement
//             try {
//                 java.lang.reflect.Method m2 = factory.getClass().getMethod("createPremis");
//                 Object ret = m2.invoke(factory);
//                 if (ret instanceof JAXBElement) {
//                     this.premisElement = (JAXBElement<Premis>) ret;
//                     this.premisRoot = premisElement.getValue();
//                 } else {
//                     // last resort: instantiate Premis and wrap into JAXBElement with QName
//                     this.premisRoot = new Premis();
//                     this.premisElement = new JAXBElement<>(new QName("http://www.loc.gov/premis/v3", "premis"), Premis.class, this.premisRoot);
//                 }
//             } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException e) {
//                 // last resort: create Premis and wrapper
//                 this.premisRoot = new Premis();
//                 this.premisElement = new JAXBElement<>(new QName("http://www.loc.gov/premis/v3", "premis"), Premis.class, this.premisRoot);
//             }
//         }

//         // If the folder already contains a premis xml, try to unmarshal it to populate premisRoot
//         Optional<Path> existing = findExistingPremisFile();
//         if (existing.isPresent()) {
//             unmarshalExisting(existing.get());
//             LOG.info("Loaded existing PREMIS from " + existing.get());
//         } else {
//             // create minimal structure: intellectualObject with objectIdentifier = SIP id
//             initializeBasicPremis();
//             LOG.info("Created new PREMIS root");
//         }
//     }

//     // find an existing premis xml file (filename contains "premis")
//     private Optional<Path> findExistingPremisFile() throws IOException {
//         try (DirectoryStream<Path> ds = Files.newDirectoryStream(sipRoot, "*.xml")) {
//             for (Path p : ds) {
//                 String n = p.getFileName().toString().toLowerCase();
//                 if (n.contains("premis")) return Optional.of(p);
//             }
//         }
//         // deeper search
//         final Path[] found = {null};
//         Files.walkFileTree(sipRoot, new SimpleFileVisitor<Path>() {
//             @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
//                 String n = file.getFileName().toString().toLowerCase();
//                 if (n.endsWith(".xml") && n.contains("premis")) {
//                     found[0] = file;
//                     return FileVisitResult.TERMINATE;
//                 }
//                 return FileVisitResult.CONTINUE;
//             }
//         });
//         return Optional.ofNullable(found[0]);
//     }

//     private void unmarshalExisting(Path xml) throws Exception {
//         Unmarshaller u = jaxb.createUnmarshaller();
//         try (InputStream is = Files.newInputStream(xml)) {
//             Object unmarshalled = u.unmarshal(is);
//             if (unmarshalled instanceof JAXBElement) {
//                 JAXBElement<?> je = (JAXBElement<?>) unmarshalled;
//                 Object val = je.getValue();
//                 if (val instanceof Premis) {
//                     this.premisRoot = (Premis) val;
//                     this.premisElement = (JAXBElement<Premis>) je;
//                     return;
//                 }
//             } else if (unmarshalled instanceof Premis) {
//                 this.premisRoot = (Premis) unmarshalled;
//                 this.premisElement = new JAXBElement<>(new QName("http://www.loc.gov/premis/v3", "premis"), Premis.class, this.premisRoot);
//                 return;
//             }
//             // fallback leave premisRoot as empty created earlier
//         }
//     }

//     private void initializeBasicPremis() {
//         // set up a minimal intellectualObject with objectIdentifier CNR = folder name
//         IntellectualObject io = factory.createIntellectualObject();
//         ObjectIdentifierComplexType oid = factory.createObjectIdentifierComplexType();
//         oid.setObjectIdentifierType("SIP-ID");
//         oid.setObjectIdentifierValue(sipRoot.getFileName().toString());
//         io.getObjectIdentifier().add(oid);

//         // objectCharacteristics: compositionLevel = 1
//         ObjectCharacteristicsComplexType oc = factory.createObjectCharacteristicsComplexType();
//         CompositionLevelComplexType compLevel = factory.createCompositionLevelComplexType();
//         compLevel.setValue("1");
//         oc.setCompositionLevel(compLevel);
//         io.setObjectCharacteristics(oc);

//         io.setSignificantProperties("Case SIP: metadata + rep1 + rep2 + schema");

//         this.premisRoot.getIntellectualObject().add(io);

//         // Add a default agent (system) and depositor
//         AgentComplexType systemAgent = factory.createAgentComplexType();
//         AgentIdentifierComplexType aid = factory.createAgentIdentifierComplexType();
//         aid.setAgentIdentifierType("system");
//         aid.setAgentIdentifierValue("JDPS-Repository");
//         systemAgent.getAgentIdentifier().add(aid);
//         systemAgent.setAgentName("JDPS Preservation System");
//         systemAgent.setAgentType("software");
//         this.premisRoot.getAgent().add(systemAgent);

//         AgentComplexType uploader = factory.createAgentComplexType();
//         AgentIdentifierComplexType uaid = factory.createAgentIdentifierComplexType();
//         uaid.setAgentIdentifierType("person");
//         uaid.setAgentIdentifierValue("uploader@example.org");
//         uploader.getAgentIdentifier().add(uaid);
//         uploader.setAgentName("Case Uploader");
//         uploader.setAgentType("human");
//         this.premisRoot.getAgent().add(uploader);

//         // simple rights
//         RightsComplexType rights = factory.createRightsComplexType();
//         RightsStatementComplexType rsc = factory.createRightsStatementComplexType();
//         rsc.setRightsBasis("statute");
//         rsc.setRightsGranted("Access restricted to authorized court staff");
//         rights.getRightsStatement().add(rsc);
//         this.premisRoot.getRights().add(rights);
//     }

//     /**
//      * Scans the SIP and ensures PREMIS <object> entries exist for files under:
//      *  - data/metadata/*.xml
//      *  - representation/*/data/*.pdf
//      *  - schema/*.xsd
//      *
//      * For each file: add ObjectType with ObjectIdentifier(FilePath), ObjectCharacteristics(size+fixity+format)
//      */
//     public void scanAndEnsureObjects() throws Exception {
//         Files.walkFileTree(sipRoot, new SimpleFileVisitor<Path>() {
//             @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
//                 try {
//                     String lower = file.getFileName().toString().toLowerCase();
//                     if (lower.endsWith(".pdf") || lower.endsWith(".xml") || lower.endsWith(".xsd")) {
//                         if (lower.contains("premis")) return FileVisitResult.CONTINUE;
//                         addOrUpdateFileObject(file);
//                     }
//                 } catch (Exception ex) {
//                     LOG.warning("scan error for file " + file + ": " + ex.getMessage());
//                 }
//                 return FileVisitResult.CONTINUE;
//             }
//         });
//     }

//     // find existing object by filepath value
//     private Optional<ObjectType> findObjectByFilePath(String rel) {
//         for (ObjectType obj : premisRoot.getObject()) {
//             for (ObjectIdentifierComplexType oid : obj.getObjectIdentifier()) {
//                 if (rel.equals(oid.getObjectIdentifierValue())) return Optional.of(obj);
//             }
//         }
//         return Optional.empty();
//     }

//     private void addOrUpdateFileObject(Path file) throws Exception {
//         String rel = sipRoot.relativize(file).toString().replace(File.separatorChar, '/');
//         Optional<ObjectType> existing = findObjectByFilePath(rel);
//         if (existing.isPresent()) {
//             updateSizeAndFixity(existing.get(), file);
//             return;
//         }

//         // Create ObjectType
//         ObjectType obj = factory.createObjectType(); // TODO: if your generated class has another name, update this
//         // objectIdentifier
//         ObjectIdentifierComplexType oid = factory.createObjectIdentifierComplexType();
//         oid.setObjectIdentifierType("FilePath");
//         oid.setObjectIdentifierValue(rel);
//         obj.getObjectIdentifier().add(oid);

//         // objectCharacteristics
//         ObjectCharacteristicsComplexType oc = factory.createObjectCharacteristicsComplexType();
//         CompositionLevelComplexType comp = factory.createCompositionLevelComplexType();
//         comp.setValue("0");
//         oc.setCompositionLevel(comp);

//         // size
//         long size = Files.size(file);
//         oc.setSize(String.valueOf(size)); // note: generated type's 'size' may be a string - adjust if necessary

//         // fixity
//         FixityComplexType fix = factory.createFixityComplexType();
//         fix.setMessageDigestAlgorithm("SHA-256");
//         fix.setMessageDigest(computeSha256(file));
//         oc.getFixity().add(fix);

//         // format & designation
//         FormatComplexType fmt = factory.createFormatComplexType();
//         FormatDesignationComplexType fd = factory.createFormatDesignationComplexType();
//         fd.setFormatName(detectFormatName(file));
//         fmt.getFormatDesignation().add(fd);
//         oc.getFormat().add(fmt);

//         // Optionally: objectCharacteristicsExtension for receivingDate (ExtensionComplexType)
//         ExtensionComplexType ext = factory.createExtensionComplexType();
//         // Many generators will create a place for arbitrary content inside ExtensionComplexType:
//         // ext.getAny().add("receivingDate:" + timestamp)  -> But generated class may expect JAXBElement or XML content
//         // We'll add a simple String inside ext.any — you might want to replace with proper JAXB element.
//         ext.getAny().add(factory.createPremisExtension("receivingDate:" + ISO.format(OffsetDateTime.now())));
//         oc.setObjectCharacteristicsExtension(ext);

//         obj.setObjectCharacteristics(oc);

//         // creatingApplication
//         CreatingApplicationComplexType ca = factory.createCreatingApplicationComplexType();
//         ca.setCreatingApplicationName("JDPS-Repository");
//         ca.setDateCreatedByApplication(fileTimeISO(file));
//         obj.setCreatingApplication(ca);

//         // Add to premisRoot
//         premisRoot.getObject().add(obj);
//         LOG.info("Added PREMIS object for " + rel);
//     }

//     // update size & fixity for existing object
//     private void updateSizeAndFixity(ObjectType obj, Path file) throws Exception {
//         ObjectCharacteristicsComplexType oc = obj.getObjectCharacteristics();
//         if (oc == null) {
//             oc = factory.createObjectCharacteristicsComplexType();
//             obj.setObjectCharacteristics(oc);
//         }
//         oc.setSize(String.valueOf(Files.size(file)));

//         FixityComplexType fix = factory.createFixityComplexType();
//         fix.setMessageDigestAlgorithm("SHA-256");
//         fix.setMessageDigest(computeSha256(file));
//         oc.getFixity().clear();
//         oc.getFixity().add(fix);

//         // update creatingApplication date
//         CreatingApplicationComplexType ca = obj.getCreatingApplication();
//         if (ca == null) {
//             ca = factory.createCreatingApplicationComplexType();
//             obj.setCreatingApplication(ca);
//         }
//         ca.setDateCreatedByApplication(fileTimeISO(file));
//         LOG.info("Updated size & fixity for " + sipRoot.relativize(file));
//     }

//     // Add an ingest event to premisRoot
//     public void addIngestEvent(String detail) {
//         EventComplexType event = factory.createEventComplexType();
//         EventIdentifierComplexType eid = factory.createEventIdentifierComplexType();
//         eid.setEventIdentifierType("eventID");
//         eid.setEventIdentifierValue("EVT-INGEST-" + System.currentTimeMillis());
//         event.getEventIdentifier().add(eid);
//         event.setEventType("ingest");

//         EventDetailInformationComplexType edi = factory.createEventDetailInformationComplexType();
//         edi.setEventDetail(detail);
//         event.setEventDetailInformation(edi);

//         EventOutcomeInformationComplexType eoi = factory.createEventOutcomeInformationComplexType();
//         eoi.getEventOutcome().add("success");
//         event.setEventOutcomeInformation(eoi);

//         // linkingAgentIdentifier
//         LinkingAgentIdentifierComplexType lAgent = factory.createLinkingAgentIdentifierComplexType();
//         lAgent.setLinkingAgentIdentifierType("system");
//         lAgent.setLinkingAgentIdentifierValue("JDPS-Repository");
//         event.getLinkingAgentIdentifier().add(lAgent);

//         premisRoot.getEvent().add(event);
//         LOG.info("Added ingest event: " + detail);
//     }

//     // helper: compute SHA-256 hex
//     private String computeSha256(Path f) throws Exception {
//         MessageDigest md = MessageDigest.getInstance("SHA-256");
//         try (InputStream is = Files.newInputStream(f)) {
//             byte[] buf = new byte[8192];
//             int r;
//             while ((r = is.read(buf)) > 0) md.update(buf, 0, r);
//         }
//         byte[] d = md.digest();
//         StringBuilder sb = new StringBuilder();
//         for (byte b : d) sb.append(String.format("%02x", b & 0xff));
//         return sb.toString();
//     }

//     private String detectFormatName(Path f) {
//         String fn = f.getFileName().toString().toLowerCase();
//         if (fn.endsWith(".pdf")) {
//             if (f.toString().toLowerCase().contains("/rep2/") || f.toString().toLowerCase().contains(FileSystems.getDefault().getSeparator() + "rep2" + FileSystems.getDefault().getSeparator())) {
//                 return "PDF/A-1B";
//             }
//             return "PDF";
//         } else if (fn.endsWith(".xml")) return "XML";
//         else if (fn.endsWith(".xsd")) return "XSD";
//         else return "UNKNOWN";
//     }

//     private String fileTimeISO(Path f) {
//         try {
//             FileTime ft = Files.getLastModifiedTime(f);
//             Instant i = ft.toInstant();
//             return ISO.format(i.atOffset(ZoneOffset.systemDefault().getRules().getOffset(i)));
//         } catch (Exception ex) {
//             return ISO.format(OffsetDateTime.now());
//         }
//     }

//     /**
//      * Marshal the premisRoot to the given output file
//      */
//     public void save(Path outFile) throws Exception {
//         Marshaller m = jaxb.createMarshaller();
//         m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
//         try (OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
//             m.marshal(premisElement, os);
//         }
//         LOG.info("Saved PREMIS to " + outFile);
//     }

//     // ---------------- demo main ----------------
//     public static void main(String[] args) throws Exception {
//         if (args.length < 1) {
//             System.err.println("Usage: PremisJaxbGenerator <sip-root> [out-premis.xml]");
//             System.exit(2);
//         }
//         Path sip = Paths.get(args[0]);
//         Path out = args.length >= 2 ? Paths.get(args[1]) : sip.resolve("odhc_premis_jaxb.xml");
//         PremisJaxbGenerator g = new PremisJaxbGenerator(sip);
//         g.scanAndEnsureObjects();
//         g.addIngestEvent("SIP ingested by PremisJaxbGenerator");
//         g.save(out);
//     }
// }
