package com.example;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.XMLConstants;

/**
 * PremisExactGenerator
 *
 * Generate PREMIS v3 XML in the exact structure similar to your eg.xml / odhc_premis_exact.xml.
 *
 * Usage:
 *   java -cp <classpath> com.example.PremisExactGenerator <sip-root> [<out-file>]
 *
 * - sip-root: path to SIP folder (contains metadata + PDFs + maybe XSD)
 * - out-file: optional output path (default: <sip-root>/odhc_premis_exact.xml)
 *
 * Notes:
 * - This is a DOM-based generator (no JAXB required). It intentionally mirrors
 *   the element layout you requested.
 * - It picks the first two .pdf files it finds (alphabetically) as rep1 originals,
 *   and creates derived rep2 converted entries for them (using the same bytes for digests if converted files are not present).
 */
public class PremisJaxbV3Generator2 {

    private static final String PREMIS_NS = "http://www.loc.gov/premis/v3";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String SCHEMA_LOC = "http://www.loc.gov/premis/v3 http://www.loc.gov/standards/premis/premis-3-0.xsd";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path sipRoot;
    private final Path outFile;

    public PremisJaxbV3Generator2(Path sipRoot, Path outFile) {
        this.sipRoot = sipRoot;
        this.outFile = outFile;
    }

    public void generate() throws Exception {
        if (!Files.isDirectory(sipRoot)) throw new IllegalArgumentException("sipRoot must be a directory: " + sipRoot);

        // collect files in top-level SIP folder (this matches the uploaded sample)
        Map<String, Path> filesByName = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sipRoot)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) filesByName.put(p.getFileName().toString(), p);
            }
        }

        // find .pdfs and .xsd
        List<Path> pdfs = new ArrayList<>();
        Path xsd = null;
        for (Path p : filesByName.values()) {
            String s = p.getFileName().toString().toLowerCase();
            if (s.endsWith(".pdf")) pdfs.add(p);
            else if (s.endsWith(".xsd")) xsd = p;
        }
        Collections.sort(pdfs, Comparator.comparing(p -> p.getFileName().toString(), String::compareToIgnoreCase));

        // determine metadata filename (heuristic)
        Path metadataFile = null;
        for (String nm : filesByName.keySet()) {
            if (nm.toLowerCase().contains("metadata")) {
                metadataFile = filesByName.get(nm);
                break;
            }
        }

        // rep1 originals: first two PDFs (if available)
        List<Path> rep1Originals = new ArrayList<>();
        for (int i = 0; i < Math.min(2, pdfs.size()); i++) rep1Originals.add(pdfs.get(i));

        // rep2 converted will be derived from rep1 originals (converted files not required)
        List<Path> rep2ConvertedSource = new ArrayList<>(rep1Originals);

        // Build DOM
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();

        // root element with prefixes and attributes
        Element root = doc.createElementNS(PREMIS_NS, "premis:premis");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:premis", PREMIS_NS);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NS);
        root.setAttributeNS(XSI_NS, "xsi:schemaLocation", SCHEMA_LOC);
        root.setAttribute("version", "3.0");
        doc.appendChild(root);

        String sipId = sipRoot.getFileName().toString();

        // intellectualObject (CNR)
        Element io = createElement(doc, "intellectualObject");
        root.appendChild(io);

        Element ioOid = createElement(doc, "objectIdentifier");
        io.appendChild(ioOid);
        ioOid.appendChild(textElement(doc, "objectIdentifierType", "CNR"));
        ioOid.appendChild(textElement(doc, "objectIdentifierValue", sipId));

        Element ioOc = createElement(doc, "objectCharacteristics");
        io.appendChild(ioOc);
        ioOc.appendChild(textElement(doc, "compositionLevel", "1"));

        io.appendChild(textElement(doc, "significantProperties", "Case SIP"));

        // Agents
        // Agent JTDR (system)
        Element agentJTDR = createElement(doc, "agent");
        root.appendChild(agentJTDR);
        Element agid1 = createElement(doc, "agentIdentifier");
        agentJTDR.appendChild(agid1);
        agid1.appendChild(textElement(doc, "agentIdentifierType", "system"));
        agid1.appendChild(textElement(doc, "agentIdentifierValue", "JTDR"));
        agentJTDR.appendChild(textElement(doc, "agentName", "JTDR"));
        agentJTDR.appendChild(textElement(doc, "agentType", "software"));

        // Agent depositor (human)
        Element agentDep = createElement(doc, "agent");
        root.appendChild(agentDep);
        Element agid2 = createElement(doc, "agentIdentifier");
        agentDep.appendChild(agid2);
        agid2.appendChild(textElement(doc, "agentIdentifierType", "depositor"));
        agid2.appendChild(textElement(doc, "agentIdentifierValue", "uploader@example.org"));
        agentDep.appendChild(textElement(doc, "agentName", "Case Uploader"));
        agentDep.appendChild(textElement(doc, "agentType", "human"));

        // Rights: statute
        Element rights = createElement(doc, "rights");
        root.appendChild(rights);
        Element rs = createElement(doc, "rightsStatement");
        rights.appendChild(rs);
        rs.appendChild(textElement(doc, "rightsBasis", "statute"));
        rs.appendChild(textElement(doc, "rightsGranted", "Access restricted to authorized user"));

        // Add metadata object (mapped path)
        if (metadataFile != null) {
            String metaRel = "data/metadata/ODHC012342025_Metadata_ecourt.xml"; // exact path as example
            appendFileObject(doc, root, metadataFile, metaRel, "XML", true, "JTDR", false);
        }

        // rep1 originals
        for (int i = 0; i < rep1Originals.size(); i++) {
            Path p = rep1Originals.get(i);
            String rel = String.format("representation/rep1/data/ODHC012342025_%d_original.pdf", i + 1);
            // for second original (i==1) attach creatingApplication as in example
            boolean createApp = (i == 1);
            appendFileObject(doc, root, p, rel, "PDF", createApp ? true : false, "JTDR", true);
        }

        // rep2 converted (derivedFrom rep1)
        for (int i = 0; i < rep2ConvertedSource.size(); i++) {
            Path src = rep2ConvertedSource.get(i);
            String rel = String.format("representation/rep2/data/ODHC012342025_%d_converted.pdf", i + 1);
            // create file entry (use src bytes for digest if converted not present)
            Element obj = appendFileObject(doc, root, src, rel, "PDF/A-1B", true, "JTDR", false);
            // add relationship derivedFrom to rep1 original
            Element relElem = createElement(doc, "relationship");
            obj.appendChild(relElem);
            Element relationshipElement = createElement(doc, "relationshipElement");
            relElem.appendChild(relationshipElement);
            relationshipElement.appendChild(textElement(doc, "relationshipSubType", "derivedFrom"));
            Element relatedObjectIdentifier = createElement(doc, "relatedObjectIdentifier");
            relationshipElement.appendChild(relatedObjectIdentifier);
            relatedObjectIdentifier.appendChild(textElement(doc, "relatedObjectIdentifierType", "FilePath"));
            String origRel = String.format("representation/rep1/data/ODHC012342025_%d_original.pdf", i + 1);
            relatedObjectIdentifier.appendChild(textElement(doc, "relatedObjectIdentifierValue", origRel));
        }

        // schema (if found)
        if (xsd != null) {
            appendFileObject(doc, root, xsd, "schema/ecourt.xsd", "XSD", false, "JTDR", false);
        }

        // Relationships linking intellectualObject to metadata / rep dirs / schema
        Element relroot = createElement(doc, "relationship");
        root.appendChild(relroot);

        // hasMetadata
        if (metadataFile != null) {
            Element re = createElement(doc, "relationshipElement"); relroot.appendChild(re);
            re.appendChild(textElement(doc, "relationshipSubType", "hasMetadata"));
            Element ro = createElement(doc, "relatedObjectIdentifier"); re.appendChild(ro);
            ro.appendChild(textElement(doc, "relatedObjectIdentifierType", "FilePath"));
            ro.appendChild(textElement(doc, "relatedObjectIdentifierValue", "data/metadata/ODHC012342025_Metadata_ecourt.xml"));
        }

        // hasRepresentation rep1 (directory)
        Element re1 = createElement(doc, "relationshipElement"); relroot.appendChild(re1);
        re1.appendChild(textElement(doc, "relationshipSubType", "hasRepresentation"));
        Element ro1 = createElement(doc, "relatedObjectIdentifier"); re1.appendChild(ro1);
        ro1.appendChild(textElement(doc, "relatedObjectIdentifierType", "directory"));
        ro1.appendChild(textElement(doc, "relatedObjectIdentifierValue", "representation/rep1/"));

        // hasRepresentation rep2 (directory)
        Element re2 = createElement(doc, "relationshipElement"); relroot.appendChild(re2);
        re2.appendChild(textElement(doc, "relationshipSubType", "hasRepresentation"));
        Element ro2 = createElement(doc, "relatedObjectIdentifier"); re2.appendChild(ro2);
        ro2.appendChild(textElement(doc, "relatedObjectIdentifierType", "directory"));
        ro2.appendChild(textElement(doc, "relatedObjectIdentifierValue", "representation/rep2/"));

        // hasSchema
        if (xsd != null) {
            Element reS = createElement(doc, "relationshipElement"); relroot.appendChild(reS);
            reS.appendChild(textElement(doc, "relationshipSubType", "hasSchema"));
            Element roS = createElement(doc, "relatedObjectIdentifier"); reS.appendChild(roS);
            roS.appendChild(textElement(doc, "relatedObjectIdentifierType", "FilePath"));
            roS.appendChild(textElement(doc, "relatedObjectIdentifierValue", "schema/ecourt.xsd"));
        }

        // write document to file (pretty)
        writeDocument(doc, outFile);
        System.out.println("Wrote PREMIS XML: " + outFile.toAbsolutePath());
    }

    /**
     * Append an <object> element for given file. Returns the appended <object> element.
     *
     * @param doc DOM document
     * @param parent parent element (root)
     * @param file path to file (used for size & digest)
     * @param relativePath value for objectIdentifierValue (FilePath)
     * @param formatName e.g. "PDF", "PDF/A-1B", "XML"
     * @param addCreatingApplication whether to add creatingApplication entry
     * @param creatingApplicationName name of creating application (JTDR)
     * @param addReceivingDate whether to add receivingDate extension
     */
    private Element appendFileObject(Document doc, Element parent, Path file, String relativePath,
                                     String formatName, boolean addCreatingApplication,
                                     String creatingApplicationName, boolean addReceivingDate) {
        Element obj = createElement(doc, "object");
        parent.appendChild(obj);

        Element oid = createElement(doc, "objectIdentifier"); obj.appendChild(oid);
        oid.appendChild(textElement(doc, "objectIdentifierType", "FilePath"));
        oid.appendChild(textElement(doc, "objectIdentifierValue", relativePath));

        Element oc = createElement(doc, "objectCharacteristics"); obj.appendChild(oc);
        oc.appendChild(textElement(doc, "compositionLevel", "0"));

        try {
            long size = Files.size(file);
            oc.appendChild(textElement(doc, "size", Long.toString(size)));
        } catch (IOException e) {
            oc.appendChild(textElement(doc, "size", "0"));
        }

        // fixity
        Element fix = createElement(doc, "fixity"); oc.appendChild(fix);
        fix.appendChild(textElement(doc, "messageDigestAlgorithm", "SHA-256"));
        try {
            fix.appendChild(textElement(doc, "messageDigest", computeSha256(file)));
        } catch (Exception ex) {
            fix.appendChild(textElement(doc, "messageDigest", ""));
        }

        // format
        Element fmt = createElement(doc, "format"); oc.appendChild(fmt);
        Element fd = createElement(doc, "formatDesignation"); fmt.appendChild(fd);
        fd.appendChild(textElement(doc, "formatName", formatName));

        if (addReceivingDate) {
            Element ext = createElement(doc, "objectCharacteristicsExtension"); oc.appendChild(ext);
            ext.appendChild(textElement(doc, "receivingDate", ISO.format(OffsetDateTime.now())));
        }

        if (addCreatingApplication) {
            Element ca = createElement(doc, "creatingApplication"); obj.appendChild(ca);
            ca.appendChild(textElement(doc, "creatingApplicationName", creatingApplicationName));
            ca.appendChild(textElement(doc, "dateCreatedByApplication", "2024-12-07T00:00:00+05:30"));
        }

        return obj;
    }

    // helper: create element in PREMIS namespace with given local name
    private Element createElement(Document doc, String localName) {
        return doc.createElementNS(PREMIS_NS, "premis:" + localName);
    }

    private Element textElement(Document doc, String localName, String text) {
        Element e = createElement(doc, localName);
        e.setTextContent(text);
        return e;
    }

    // compute sha256 hex of file
    private String computeSha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) md.update(buf, 0, r);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // pretty-print DOM to file
    private void writeDocument(Document doc, Path out) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        DOMSource src = new DOMSource(doc);
        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            StreamResult sr = new StreamResult(os);
            t.transform(src, sr);
        }
    }

    // --- main ---
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PremisJaxbV3Generator2 <sip-root> [<out-file>]");
            System.exit(2);
        }
        Path sip = Paths.get(args[0]);
        Path out = args.length >= 2 ? Paths.get(args[1]) : sip.resolve("odhc_premis_jaxb_v3.xml");
        PremisJaxbV3Generator2 gen = new PremisJaxbV3Generator2(sip, out);
        gen.generate();
    }
}
