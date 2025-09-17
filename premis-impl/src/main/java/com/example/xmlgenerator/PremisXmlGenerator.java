package com.example.xmlgenerator;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;

import com.example.PremisJaxbV3Generator;

import gov.loc.premis.v3.IntellectualEntity;
import gov.loc.premis.v3.ObjectComplexType;
import gov.loc.premis.v3.ObjectFactory;
import gov.loc.premis.v3.ObjectIdentifierComplexType;
import gov.loc.premis.v3.PremisComplexType;
import gov.loc.premis.v3.RelatedObjectIdentifierComplexType;
import gov.loc.premis.v3.RelationshipComplexType;
import gov.loc.premis.v3.SignificantPropertiesComplexType;
import gov.loc.premis.v3.StringPlusAuthority;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.time.format.DateTimeFormatter;



public class PremisXmlGenerator {
    private static final Logger LOG = Logger.getLogger(PremisJaxbV3Generator.class.getName());
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String PREMIS_NS = "http://www.loc.gov/premis/v3";
    private final Path sipRoot;
    private final JAXBContext jaxb;
    private final ObjectFactory factory;
    private PremisComplexType premisRoot;
    private JAXBElement<PremisComplexType> premisElement;


     public PremisXmlGenerator(Path sipRoot) throws Exception {
        if (sipRoot == null || !Files.isDirectory(sipRoot)) {
            throw new IllegalArgumentException("sipRoot must be an existing directory");
        }
        this.sipRoot = sipRoot;
        this.jaxb = JAXBContext.newInstance("gov.loc.premis.v3");
        this.factory = new ObjectFactory();

        createOrLoadPremisRoot();
        try {
            Method setVersion = this.premisRoot.getClass().getMethod("setVersion", String.class);
            setVersion.invoke(this.premisRoot, "3.0");
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void createOrLoadPremisRoot() {
        try {
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
        this.premisRoot = new PremisComplexType();
        this.premisElement = new JAXBElement<>(
                new QName(PREMIS_NS, "premis", "premis"),
                PremisComplexType.class,
                this.premisRoot);

    }

    public void save(Path out) throws Exception {
        Marshaller marshaller = jaxb.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_SCHEMA_LOCATION,
                PREMIS_NS + " http://www.loc.gov/standards/premis/premis-3-0.xsd");
        try {
            com.sun.xml.bind.marshaller.NamespacePrefixMapper mapper =
                    new com.sun.xml.bind.marshaller.NamespacePrefixMapper() {
                        @Override
                        public String getPreferredPrefix(String namespaceUri, String suggestion, boolean requirePrefix) {
                            if (PREMIS_NS.equals(namespaceUri)) return "premis";
                            if ("http://www.w3.org/2001/XMLSchema-instance".equals(namespaceUri)) return "xsi";
                            return suggestion;
                        }
                    };
            marshaller.setProperty("com.sun.xml.bind.namespacePrefixMapper", mapper);
        } catch (Throwable t) {
        }

        try {
            Method setVersion = this.premisRoot.getClass().getMethod("setVersion", String.class);
            setVersion.invoke(this.premisRoot, "3.0");
        } catch (NoSuchMethodException ignored) { /* ignore */ }

        Path tmp = Files.createTempFile("premis-", ".xml");
        try (OutputStream os = Files.newOutputStream(tmp)) {
            marshaller.marshal(this.premisElement, os);
        }

        String xml = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8);
        if (!xml.contains("premis:premis")) {
            xml = xml.replaceFirst("<premis\\s+xmlns=\""+Pattern.quote(PREMIS_NS)+"\"",
                    "<premis:premis xmlns:premis=\"" + PREMIS_NS + "\"");
            xml = xml.replaceFirst("</premis>", "</premis:premis>");
        }

        byte[] outBytes = xml.getBytes(StandardCharsets.UTF_8);
        Files.write(out, outBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(tmp);
    }


    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PremisJaxbV3Generator <sip-root> [out-premis.xml]");
            System.exit(2);
        }
        Path sip = Paths.get(args[0]);
        Path out = args.length >= 2 ? Paths.get(args[1]) : sip.resolve("odhc_premis_v3.xml");
        PremisXmlGenerator g = new PremisXmlGenerator(sip);
        // g.scanAndEnsureObjects();
        // g.addIngestEvent("SIP ingested by PremisXmlGenerator");
        g.save(out);
    }

public void addIntellectualEntityObject(String cnr) {
    IntellectualEntity ie = new IntellectualEntity();

    // --- objectIdentifier ---
    ObjectIdentifierComplexType oid = new ObjectIdentifierComplexType();

    // objectIdentifierType expects StringPlusAuthority
    oid.setObjectIdentifierType(spa("CNR"));

    // objectIdentifierValue is usually a plain String in many XJC outputs;
    // keep as String — if your generated method requires StringPlusAuthority instead,
    // change to: oid.setObjectIdentifierValue(spa(cnr)); (IDE will show correct signature)
    try {
        // try set as plain String first
        oid.setObjectIdentifierValue(cnr);
    } catch (Throwable t) {
        // fallback: try the setter that accepts StringPlusAuthority
        try {
            oid.getClass().getMethod("setObjectIdentifierValue", StringPlusAuthority.class)
               .invoke(oid, spa(cnr));
        } catch (Throwable ignored) {
            // if still failing, leave — at least type was set correctly
        }
    }
    ie.getObjectIdentifier().add(oid);

    // --- significantProperties ---
    SignificantPropertiesComplexType sp = new SignificantPropertiesComplexType();
    // Many generated classes use plain String for the "value" of significantProperties.
    // Use the simple setter:
    JAXBElement<String> spVal =
        new JAXBElement<>(new QName(PREMIS_NS, "significantPropertiesValue"), String.class, "Case SIP");
    sp.getContent().add(spVal);

    ie.getSignificantProperties().add(sp);

    // --- relationship: hasMetadata ---
    RelationshipComplexType relMeta = new RelationshipComplexType();
    relMeta.setRelationshipType("structural");
    relMeta.setRelationshipSubType("hasMetadata");

    RelatedObjectIdentifierComplexType roiMeta = new RelatedObjectIdentifierComplexType();
    // relatedObjectIdentifierType often expects StringPlusAuthority
    roiMeta.setRelatedObjectIdentifierType(spa("FilePath"));

    // relatedObjectIdentifierValue often expects plain String; try that first:
    try {
        roiMeta.setRelatedObjectIdentifierValue("data/metadata/" + cnr + "_Metadata_ecourt.xml");
    } catch (Throwable t) {
        // fallback to spa if API expects StringPlusAuthority
        try {
            roiMeta.getClass().getMethod("setRelatedObjectIdentifierValue", StringPlusAuthority.class)
                .invoke(roiMeta, spa("data/metadata/" + cnr + "_Metadata_ecourt.xml"));
        } catch (Throwable ignored) {}
    }
    relMeta.getRelatedObjectIdentifier().add(roiMeta);
    ie.getRelationship().add(relMeta);

    // --- relationship: hasRepresentation -> representation/rep1/ ---
    RelationshipComplexType relRep1 = new RelationshipComplexType();
    relRep1.setRelationshipType("structural");
    relRep1.setRelationshipSubType("hasRepresentation");
    RelatedObjectIdentifierComplexType roiRep1 = new RelatedObjectIdentifierComplexType();
    roiRep1.setRelatedObjectIdentifierType(spa("directory"));
    try {
        roiRep1.setRelatedObjectIdentifierValue("representation/rep1/");
    } catch (Throwable t) {
        try {
            roiRep1.getClass().getMethod("setRelatedObjectIdentifierValue", StringPlusAuthority.class)
                  .invoke(roiRep1, spa("representation/rep1/"));
        } catch (Throwable ignored) {}
    }
    relRep1.getRelatedObjectIdentifier().add(roiRep1);
    ie.getRelationship().add(relRep1);

    // --- relationship: hasRepresentation -> representation/rep2/ ---
    RelationshipComplexType relRep2 = new RelationshipComplexType();
    relRep2.setRelationshipType("structural");
    relRep2.setRelationshipSubType("hasRepresentation");
    RelatedObjectIdentifierComplexType roiRep2 = new RelatedObjectIdentifierComplexType();
    roiRep2.setRelatedObjectIdentifierType(spa("directory"));
    try {
        roiRep2.setRelatedObjectIdentifierValue("representation/rep2/");
    } catch (Throwable t) {
        try {
            roiRep2.getClass().getMethod("setRelatedObjectIdentifierValue", StringPlusAuthority.class)
                  .invoke(roiRep2, spa("representation/rep2/"));
        } catch (Throwable ignored) {}
    }
    relRep2.getRelatedObjectIdentifier().add(roiRep2);
    ie.getRelationship().add(relRep2);

    // --- relationship: hasSchema ---
    RelationshipComplexType relSchema = new RelationshipComplexType();
    relSchema.setRelationshipType("structural");
    relSchema.setRelationshipSubType("hasSchema");
    RelatedObjectIdentifierComplexType roiSchema = new RelatedObjectIdentifierComplexType();
    roiSchema.setRelatedObjectIdentifierType(spa("FilePath"));
    try {
        roiSchema.setRelatedObjectIdentifierValue("schema/ecourt.xsd");
    } catch (Throwable t) {
        try {
            roiSchema.getClass().getMethod("setRelatedObjectIdentifierValue", StringPlusAuthority.class)
                  .invoke(roiSchema, spa("schema/ecourt.xsd"));
        } catch (Throwable ignored) {}
    }
    relSchema.getRelatedObjectIdentifier().add(roiSchema);
    ie.getRelationship().add(relSchema);

    // add to root
    this.premisRoot.getObject().add(ie);
}


private StringPlusAuthority spa(String value) {
    StringPlusAuthority s = new StringPlusAuthority();
    // typical generated class provides setValue — try it:
    try {
        Method setVal = s.getClass().getMethod("setValue", String.class);
        setVal.invoke(s, value);
    } catch (Throwable t) {
        // fallback: try setString or setContent or setSignificantPropertiesValue variants if present
        try {
            Method m = s.getClass().getMethod("setString", String.class);
            m.invoke(s, value);
        } catch (Throwable ignored) {
            try {
                Method m2 = s.getClass().getMethod("setSignificantPropertiesValue", String.class);
                m2.invoke(s, value);
            } catch (Throwable ignored2) {
                // If none of the setters are present, try to set the field directly (last resort)
                try {
                    java.lang.reflect.Field f = s.getClass().getDeclaredField("value");
                    f.setAccessible(true);
                    f.set(s, value);
                } catch (Throwable ignored3) {}
            }
        }
    }
    return s;
}

    
}