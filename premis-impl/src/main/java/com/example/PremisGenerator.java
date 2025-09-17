package com.example;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import org.w3c.dom.*;

/**
 * PremisGenerator
 *
 * Generates a PREMIS v3 XML for a given input folder (the folder that contains data/metadata/representation).
 *
 * How to use:
 *  - Generate JAXB classes from premis-3-0.xsd (xjc) OR leave GENERATED_PACKAGE=null to use DOM fallback.
 *  - Call generatePremis(new File("path/to/ODHC010879122024"), new File("output/premis.xml"));
 *
 * Notes:
 *  - If you have generated classes, set GENERATED_PACKAGE to the package used by your generated classes,
 *    e.g. "gov.loc.premis.v3" or "org.loc.premis.v3" etc.
 *  - This program computes filename, size and SHA-256 checksum. Add extra PREMIS fields as needed.
 *
 * References: PREMIS v3 documentation & schema. :contentReference[oaicite:1]{index=1}
 */
public class PremisGenerator {

    // If you used xjc to generate classes from premis-3-0.xsd, set this to the generated package name.
    // e.g. "gov.loc.premis.v3" or the package your build created. If left null, generator will use DOM fallback.
    private static final String GENERATED_PACKAGE = "gov.loc.premis.v3"; // <- change to your generated package or set to null

    // PREMIS namespace and prefix for v3
    private static final String PREMIS_NS = "http://www.loc.gov/standards/premis/v3";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String PREMIS_SCHEMA_LOCATION = "http://www.loc.gov/standards/premis/v3/premis-v3-0.xsd";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java PremisGenerator <input-folder> <output-premis.xml>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File out = new File(args[1]);
        generatePremis(input, out);
        System.out.println("PREMIS generated at: " + out.getAbsolutePath());
    }

    /**
     * Generate a PREMIS XML for the given input folder.
     * If JAXB-generated classes are present (and GENERATED_PACKAGE is set), tries to use them.
     * Otherwise falls back to DOM builder.
     */
    public static void generatePremis(File inputFolder, File outputXml) throws Exception {
        if (!inputFolder.exists() || !inputFolder.isDirectory()) {
            throw new IllegalArgumentException("inputFolder must be an existing directory");
        }

        // find files under representation directories
        List<File> filesToDescribe = new ArrayList<>();
        // Typical path: <inputFolder>/data/representation/<repName>/*.pdf
        File data = new File(inputFolder, "data");
        if (data.exists()) {
            File rep = new File(data, "representation");
            if (rep.exists()) {
                // walk one level deep: representation/*/*.pdf
                File[] reps = rep.listFiles(File::isDirectory);
                if (reps != null) {
                    for (File r : reps) {
                        File[] fs = r.listFiles((f, n) -> n.toLowerCase().endsWith(".pdf"));
                        if (fs != null) {
                            filesToDescribe.addAll(Arrays.asList(fs));
                        }
                    }
                }
            }
        }

        if (GENERATED_PACKAGE != null && tryUseJaxbGenerated(filesToDescribe, inputFolder, outputXml)) {
            return;
        }

        // Fallback DOM writer
        writePremisWithDom(filesToDescribe, inputFolder, outputXml);
    }

    // Attempt to use generated JAXB classes via reflection.
    // Returns true if successful.
    private static boolean tryUseJaxbGenerated(List<File> files, File inputFolder, File output) {
        try {
            // Attempt to load ObjectFactory
            String factoryClassName = GENERATED_PACKAGE + ".ObjectFactory";
            Class<?> factoryClass = Class.forName(factoryClassName);
            Object factory = factoryClass.getDeclaredConstructor().newInstance();

            // Try to find method createPremis or createPremisType
            Method createPremisMethod = null;
            for (Method m : factoryClass.getMethods()) {
                if (m.getName().startsWith("create") && (m.getReturnType().getSimpleName().toLowerCase().contains("premis")
                        || m.getName().equalsIgnoreCase("createPremis") || m.getName().equalsIgnoreCase("createPremisType"))) {
                    createPremisMethod = m;
                    break;
                }
            }

            final Object premisRoot;
            if (createPremisMethod != null) {
                premisRoot = createPremisMethod.invoke(factory);
            } else {
                // Try to locate PremisType class and instantiate it
                try {
                    Class<?> premisTypeClass = Class.forName(GENERATED_PACKAGE + ".PremisType");
                    premisRoot = premisTypeClass.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Could not find a createPremis factory method nor PremisType class in " + GENERATED_PACKAGE, e);
                }
            }

            // Try to set version attribute: setVersion("3.0") or setVersion on JAXBElement wrapper
            try {
                Method setVersion = premisRoot.getClass().getMethod("setVersion", String.class);
                setVersion.invoke(premisRoot, "3.0");
            } catch (NoSuchMethodException ignore) {
                // maybe it's a JAXBElement; skip
            }

            // We need to create file/object entries using factory methods like createObject() and various subelements.
            // We'll try to find createObject or createObjectType
            Method createObjectMethod = null;
            for (Method m : factoryClass.getMethods()) {
                if (m.getName().equals("createObject") || m.getName().toLowerCase().contains("createobjecttype")) {
                    createObjectMethod = m;
                    break;
                }
            }

            // We'll try to find a method on root to get list of objects: getObject() or getObjects()
            Method getObjectListMethod = null;
            for (Method m : premisRoot.getClass().getMethods()) {
                if (m.getName().startsWith("get") && (m.getName().toLowerCase().contains("object"))) {
                    // pick first getter returning java.util.List
                    if (List.class.isAssignableFrom(m.getReturnType())) {
                        getObjectListMethod = m;
                        break;
                    }
                }
            }

            if (getObjectListMethod == null) {
                // maybe there is a JAXBElement wrapper needed; try wrapping later with JAXB marshaller
                // For safety, fall back to DOM path
                throw new RuntimeException("Cannot find a getObject() List accessor on Premis root class. Falling back.");
            }

            @SuppressWarnings("unchecked")
            List<Object> objectList = (List<Object>) getObjectListMethod.invoke(premisRoot);

            // For each file create an object element and populate some basic fields via reflection.
            for (File f : files) {
                Object obj;
                if (createObjectMethod != null) {
                    obj = createObjectMethod.invoke(factory);
                } else {
                    // try to find an ObjectType class
                    Class<?> objectTypeClass = Class.forName(GENERATED_PACKAGE + ".ObjectType");
                    obj = objectTypeClass.getDeclaredConstructor().newInstance();
                }

                // Typical PREMIS object fields we will try to set (by reflection if setters exist):
                // - objectIdentifier (with objectIdentifierType and value)
                // - objectCategory or objectType (file)
                // - objectName
                // - objectCharacteristics (size, compositionLevel)
                // - objectCharacteristics -> fixity/size/checksum

                // 1) objectIdentifier
                try {
                    // Create identifier container via factory if available
                    Method createObjIdMethod = null;
                    for (Method m : factoryClass.getMethods()) {
                        if (m.getName().toLowerCase().contains("createobjectidentif") ) { createObjIdMethod = m; break; }
                    }
                    Object objId = null;
                    if (createObjIdMethod != null) {
                        objId = createObjIdMethod.invoke(factory);
                    } else {
                        // instantiate class by name
                        try {
                            Class<?> cls = Class.forName(GENERATED_PACKAGE + ".ObjectIdentifierType");
                            objId = cls.getDeclaredConstructor().newInstance();
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                    if (objId != null) {
                        // set value: many schemas offer setObjectIdentifierValue(String) & setObjectIdentifierType(String)
                        try {
                            Method setVal = objId.getClass().getMethod("setObjectIdentifierValue", String.class);
                            setVal.invoke(objId, f.getName());
                        } catch (NoSuchMethodException ignore) {
                            // try setValue
                            try {
                                Method setVal2 = objId.getClass().getMethod("setValue", String.class);
                                setVal2.invoke(objId, f.getName());
                            } catch (NoSuchMethodException ignored) {}
                        }
                        try {
                            Method setType = objId.getClass().getMethod("setObjectIdentifierType", String.class);
                            setType.invoke(objId, "filename");
                        } catch (NoSuchMethodException ignored) {}

                        // find setter on obj: setObjectIdentifier or getObjectIdentifier().add(...)
                        boolean set = false;
                        try {
                            Method setObjIdOnObj = obj.getClass().getMethod("setObjectIdentifier", objId.getClass());
                            setObjIdOnObj.invoke(obj, objId);
                            set = true;
                        } catch (NoSuchMethodException ignored) {}

                        if (!set) {
                            // try getObjectIdentifierList and add
                            for (Method m : obj.getClass().getMethods()) {
                                if (m.getName().startsWith("get") && List.class.isAssignableFrom(m.getReturnType())
                                        && m.getName().toLowerCase().contains("objectidentifier")) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> l = (List<Object>) m.invoke(obj);
                                    l.add(objId);
                                    set = true;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // best-effort: ignore
                }

                // 2) objectCategory / objectType - set to "file"
                try {
                    Method setCategory = obj.getClass().getMethod("setObjectCategory", String.class);
                    setCategory.invoke(obj, "file");
                } catch (NoSuchMethodException ignore) {}

                // 3) objectName
                try {
                    Method setName = obj.getClass().getMethod("setObjectName", String.class);
                    setName.invoke(obj, f.getName());
                } catch (NoSuchMethodException e) {
                    // maybe there is a nested objectName element type; try find setter accepting an object
                    for (Method m : factoryClass.getMethods()) {
                        if (m.getName().toLowerCase().contains("createobjectname")) {
                            try {
                                Object objName = m.invoke(factory);
                                // try setValue / setObjectNameValue
                                try {
                                    Method setVal = objName.getClass().getMethod("setObjectNameValue", String.class);
                                    setVal.invoke(objName, f.getName());
                                } catch (NoSuchMethodException ex) {
                                    try {
                                        Method setVal2 = objName.getClass().getMethod("setValue", String.class);
                                        setVal2.invoke(objName, f.getName());
                                    } catch (NoSuchMethodException ignored) {}
                                }
                                // attach: getObjectNameList().add(objName) or setObjectName(objName)
                                boolean attached = false;
                                for (Method mm : obj.getClass().getMethods()) {
                                    if (mm.getName().startsWith("get") && List.class.isAssignableFrom(mm.getReturnType())
                                            && mm.getName().toLowerCase().contains("objectname")) {
                                        @SuppressWarnings("unchecked")
                                        List<Object> l = (List<Object>) mm.invoke(obj);
                                        l.add(objName);
                                        attached = true;
                                        break;
                                    }
                                }
                                if (!attached) {
                                    try {
                                        Method setNameMethod = obj.getClass().getMethod("setObjectName", objName.getClass());
                                        setNameMethod.invoke(obj, objName);
                                    } catch (NoSuchMethodException ignored) {}
                                }
                                break;
                            } catch (Throwable ignore) {}
                        }
                    }
                }

                // 4) objectCharacteristics: size and fixity (checksum)
                try {
                    // create objectCharacteristics via factory or class
                    Object objChar = null;
                    for (Method m : factoryClass.getMethods()) {
                        if (m.getName().toLowerCase().contains("createobjectcharacteristics")) {
                            objChar = m.invoke(factory);
                            break;
                        }
                    }
                    if (objChar == null) {
                        try {
                            Class<?> cls = Class.forName(GENERATED_PACKAGE + ".ObjectCharacteristicsType");
                            objChar = cls.getDeclaredConstructor().newInstance();
                        } catch (Throwable ignore) {}
                    }

                    if (objChar != null) {
                        // set size (byteCount)
                        try {
                            Method setSize = objChar.getClass().getMethod("setSize", long.class);
                            setSize.invoke(objChar, f.length());
                        } catch (NoSuchMethodException ns) {
                            // try setSizeInBytes / setSizeValue etc
                            try {
                                Method setSize2 = objChar.getClass().getMethod("setSizeInBytes", long.class);
                                setSize2.invoke(objChar, f.length());
                            } catch (Exception ignored) {}
                        }

                        // create fixity/checksum element
                        String sha256 = computeSha256Hex(f);
                        // create fixity structure from factory if present
                        Object fixity = null;
                        for (Method m : factoryClass.getMethods()) {
                            if (m.getName().toLowerCase().contains("createfixity") || m.getName().toLowerCase().contains("createobjectfixity")) {
                                try { fixity = m.invoke(factory); break; } catch (Throwable ignored) {}
                            }
                        }
                        if (fixity == null) {
                            try { Class<?> fixityCls = Class.forName(GENERATED_PACKAGE + ".FixityType"); fixity = fixityCls.getDeclaredConstructor().newInstance(); }
                            catch (Throwable ignored) {}
                        }
                        if (fixity != null) {
                            // set algorithm and value
                            try {
                                Method setAlg = fixity.getClass().getMethod("setMessageDigestAlgorithm", String.class);
                                setAlg.invoke(fixity, "SHA-256");
                            } catch (NoSuchMethodException ignored) { }
                            try {
                                Method setVal = fixity.getClass().getMethod("setMessageDigest", String.class);
                                setVal.invoke(fixity, sha256);
                            } catch (NoSuchMethodException ignored) { }
                            // attach fixity to objChar
                            boolean attached = false;
                            try {
                                Method setFix = objChar.getClass().getMethod("setFixity", fixity.getClass());
                                setFix.invoke(objChar, fixity);
                                attached = true;
                            } catch (NoSuchMethodException ignore) {}
                            if (!attached) {
                                for (Method mm : objChar.getClass().getMethods()) {
                                    if (mm.getName().startsWith("get") && List.class.isAssignableFrom(mm.getReturnType()) && mm.getName().toLowerCase().contains("fixity")) {
                                        @SuppressWarnings("unchecked")
                                        List<Object> l = (List<Object>) mm.invoke(objChar);
                                        l.add(fixity);
                                        break;
                                    }
                                }
                            }
                        }

                        // attach objectCharacteristics to obj
                        boolean attached = false;
                        try {
                            Method setOC = obj.getClass().getMethod("setObjectCharacteristics", objChar.getClass());
                            setOC.invoke(obj, objChar);
                            attached = true;
                        } catch (NoSuchMethodException ignore) {}
                        if (!attached) {
                            for (Method mm : obj.getClass().getMethods()) {
                                if (mm.getName().startsWith("get") && List.class.isAssignableFrom(mm.getReturnType()) && mm.getName().toLowerCase().contains("objectcharacteristics")) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> l = (List<Object>) mm.invoke(obj);
                                    l.add(objChar);
                                    break;
                                }
                            }
                        }
                    }

                } catch (Throwable t) {
                    // ignore - best effort
                }

                // Finally, add object to the root object list
                objectList.add(obj);
            }

            // Marshal premisRoot to file with JAXB
            // Build JAXBContext for the generated package
            JAXBContext jc = JAXBContext.newInstance(GENERATED_PACKAGE);
            Marshaller m = jc.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

            // If premisRoot is a JAXBElement wrapper, marshal it directly
            if (premisRoot.getClass().getName().startsWith("javax.xml.bind.JAXBElement")) {
                m.marshal(premisRoot, output);
            } else {
                // Try to find an @XmlRootElement or create a JAXBElement wrapper with QName
                QName q = new QName(PREMIS_NS, "premis");
                JAXBElement<?> wrapper = new JAXBElement<>(q, (Class) premisRoot.getClass(), premisRoot);
                m.marshal(wrapper, output);
            }

            return true;
        } catch (Throwable t) {
            System.err.println("JAXB-generated-class path failed or not available: " + t.getMessage());
            // t.printStackTrace();
            return false;
        }
    }

    // DOM fallback writer: create a PREMIS structure and write to output
    private static void writePremisWithDom(List<File> files, File inputFolder, File outputXml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();

        Element premis = doc.createElementNS(PREMIS_NS, "premis:premis");
        premis.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:premis", PREMIS_NS);
        premis.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi", XSI_NS);
        premis.setAttributeNS(XSI_NS, "xsi:schemaLocation", PREMIS_NS + " " + PREMIS_SCHEMA_LOCATION);
        premis.setAttribute("version", "3.0");
        doc.appendChild(premis);

        // Add a simple premis:object element per file
        for (File f : files) {
            Element objectEl = doc.createElementNS(PREMIS_NS, "premis:object");
            objectEl.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "premis:file");
            premis.appendChild(objectEl);

            // objectIdentifier
            Element objId = doc.createElementNS(PREMIS_NS, "premis:objectIdentifier");
            Element objIdType = doc.createElementNS(PREMIS_NS, "premis:objectIdentifierType");
            objIdType.setTextContent("filename");
            Element objIdVal = doc.createElementNS(PREMIS_NS, "premis:objectIdentifierValue");
            objIdVal.setTextContent(f.getName());
            objId.appendChild(objIdType);
            objId.appendChild(objIdVal);
            objectEl.appendChild(objId);

            // objectCategory
            Element objCat = doc.createElementNS(PREMIS_NS, "premis:objectCategory");
            objCat.setTextContent("file");
            objectEl.appendChild(objCat);

            // objectCharacteristics
            Element objChar = doc.createElementNS(PREMIS_NS, "premis:objectCharacteristics");
            Element composition = doc.createElementNS(PREMIS_NS, "premis:compositionLevel");
            composition.setTextContent("0");
            objChar.appendChild(composition);

            Element sizeEl = doc.createElementNS(PREMIS_NS, "premis:size");
            sizeEl.setTextContent(String.valueOf(f.length()));
            objChar.appendChild(sizeEl);

            // Fixity
            Element fixity = doc.createElementNS(PREMIS_NS, "premis:fixity");
            Element alg = doc.createElementNS(PREMIS_NS, "premis:messageDigestAlgorithm");
            alg.setTextContent("SHA-256");
            Element digest = doc.createElementNS(PREMIS_NS, "premis:messageDigest");
            digest.setTextContent(computeSha256Hex(f));
            fixity.appendChild(alg);
            fixity.appendChild(digest);
            objChar.appendChild(fixity);

            objectEl.appendChild(objChar);

            // objectName
            Element oname = doc.createElementNS(PREMIS_NS, "premis:objectName");
            Element onameValue = doc.createElementNS(PREMIS_NS, "premis:objectNameValue");
            onameValue.setTextContent(f.getName());
            oname.appendChild(onameValue);
            objectEl.appendChild(oname);

            // formatIdentification (basic)
            Element format = doc.createElementNS(PREMIS_NS, "premis:format");
            Element formatName = doc.createElementNS(PREMIS_NS, "premis:formatDesignation");
            Element formatLabel = doc.createElementNS(PREMIS_NS, "premis:formatName");
            formatLabel.setTextContent(detectMimeType(f));
            formatName.appendChild(formatLabel);
            format.appendChild(formatName);
            objectEl.appendChild(format);
        }

        // write to file with pretty print
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer trans = tf.newTransformer();
        trans.setOutputProperty(OutputKeys.INDENT, "yes");
        trans.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        trans.transform(new DOMSource(doc), new StreamResult(outputXml));
    }

    private static String computeSha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > -1) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String detectMimeType(File f) {
        try {
            String t = Files.probeContentType(f.toPath());
            return t == null ? "application/pdf" : t;
        } catch (IOException e) {
            return "application/pdf";
        }
    }
}
