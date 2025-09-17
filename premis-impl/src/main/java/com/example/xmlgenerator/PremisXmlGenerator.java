package com.example.xmlgenerator;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;

import com.example.PremisJaxbV3Generator;

import gov.loc.premis.v3.CreatingApplicationComplexType;
import gov.loc.premis.v3.File;
import gov.loc.premis.v3.FixityComplexType;
import gov.loc.premis.v3.FormatComplexType;
import gov.loc.premis.v3.FormatDesignationComplexType;
import gov.loc.premis.v3.IntellectualEntity;
import gov.loc.premis.v3.ObjectCharacteristicsComplexType;
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
import java.math.BigInteger;
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

        // ensure first premis:object has xsi:type attribute (if not already present)
        if (!xml.contains("xsi:type=\"premis:intellectualEntity\"")) {
            xml = xml.replaceFirst("<premis:object(\\s*)([^>]*)>",
                                "<premis:object xsi:type=\"premis:intellectualEntity\" $2>");
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
        g.addIntellectualEntityObject("ODHC010879122024");
        g.addFileObjectUsingJaxbClasses(
            "data/metadata/ODHC012342025_Metadata_ecourt.xml", // verify filename
            0L,
            "SHA-256",
            "SHA256_METADATA",
            "XML",
            "JTDR",
            "2024-12-07T00:00:00+05:30"
        );

        // g.scanAndEnsureObjects();
        // g.addIngestEvent("SIP ingested by PremisXmlGenerator");
        g.save(out);
    }

public void addIntellectualEntityObject(String cnr) {
    IntellectualEntity ie = new IntellectualEntity();
    ObjectIdentifierComplexType oid = new ObjectIdentifierComplexType();

    oid.setObjectIdentifierType(spa("CNR"));

    try {
        oid.setObjectIdentifierValue(cnr);
    } catch (Throwable t) {
        try {
            oid.getClass().getMethod("setObjectIdentifierValue", StringPlusAuthority.class)
               .invoke(oid, spa(cnr));
        } catch (Throwable ignored) {
        }
    }
    ie.getObjectIdentifier().add(oid);

    SignificantPropertiesComplexType sp = new SignificantPropertiesComplexType();
    JAXBElement<String> spVal =
        new JAXBElement<>(new QName(PREMIS_NS, "significantPropertiesValue"), String.class, "Case SIP");
    sp.getContent().add(spVal);

    ie.getSignificantProperties().add(sp);

    RelationshipComplexType relMeta = new RelationshipComplexType();
    relMeta.setRelationshipType(spa("structural"));
    relMeta.setRelationshipSubType(spa("hasMetadata"));

    RelatedObjectIdentifierComplexType roiMeta = new RelatedObjectIdentifierComplexType();
    roiMeta.setRelatedObjectIdentifierType(spa("FilePath"));

    try {
        roiMeta.setRelatedObjectIdentifierValue("data/metadata/" + cnr + "_Metadata_ecourt.xml");
    } catch (Throwable t) {
        try {
            roiMeta.getClass().getMethod("setRelatedObjectIdentifierValue", StringPlusAuthority.class)
                .invoke(roiMeta, spa("data/metadata/" + cnr + "_Metadata_ecourt.xml"));
        } catch (Throwable ignored) {}
    }
    relMeta.getRelatedObjectIdentifier().add(roiMeta);
    ie.getRelationship().add(relMeta);

    RelationshipComplexType relRep1 = new RelationshipComplexType();
    relRep1.setRelationshipType(spa("structural"));
    relRep1.setRelationshipSubType(spa("hasRepresentation"));
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

    RelationshipComplexType relRep2 = new RelationshipComplexType();
    relRep2.setRelationshipType(spa("structural"));
    relRep2.setRelationshipSubType(spa("hasRepresentation"));
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

    RelationshipComplexType relSchema = new RelationshipComplexType();
    relSchema.setRelationshipType(spa("structural"));
    relSchema.setRelationshipSubType(spa("hasSchema"));
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



/**
 * Add a PREMIS file object using generated JAXB classes (no raw XML fragments).
 * This method uses direct setters where available and small reflection fallbacks
 * when a setter expects StringPlusAuthority or the property is modeled as a list.
 */
public void addFileObjectUsingJaxbClasses(String objectIdentifierValue,
                                          long sizeBytes,
                                          String digestAlgo,
                                          String digestValue,
                                          String formatName,
                                          String creatingAppName,
                                          String dateCreatedByApp) {
    try {
        // 1) create File instance
        File file = new File();

        // 2) objectIdentifier
        ObjectIdentifierComplexType oid = new ObjectIdentifierComplexType();
        // objectIdentifierType often expects StringPlusAuthority
        try {
            oid.setObjectIdentifierType(spa("FilePath"));
        } catch (Throwable t) {
            // fallback to plain String setter if present
            try {
                Method m = oid.getClass().getMethod("setObjectIdentifierType", String.class);
                m.invoke(oid, "FilePath");
            } catch (Throwable ignored) {}
        }
        // objectIdentifierValue may be String or StringPlusAuthority
        try {
            oid.setObjectIdentifierValue(objectIdentifierValue);
        } catch (Throwable t) {
            try {
                Method m = oid.getClass().getMethod("setObjectIdentifierValue", StringPlusAuthority.class);
                m.invoke(oid, spa(objectIdentifierValue));
            } catch (Throwable ignored) {
                // if neither setter exists, try to add via getContent() as JAXBElement (rare for ObjectIdentifier)
                try {
                    Method getContent = oid.getClass().getMethod("getContent");
                    @SuppressWarnings("unchecked")
                    java.util.List<JAXBElement<?>> list = (java.util.List<JAXBElement<?>>) getContent.invoke(oid);
                    JAXBElement<String> je = new JAXBElement<>(new QName(PREMIS_NS, "objectIdentifierValue"), String.class, objectIdentifierValue);
                    list.add(je);
                } catch (Throwable ignored2) {}
            }
        }
        // attach to file
        file.getObjectIdentifier().add(oid);

        // 3) objectCharacteristics
        ObjectCharacteristicsComplexType oc = new ObjectCharacteristicsComplexType();

        // 3.a compositionLevel -> many bindings expose getCompositionLevel() or setCompositionLevel(...)
        try {
            // try setter that accepts BigInteger or long
            try {
                Method setComp = oc.getClass().getMethod("setCompositionLevel", BigInteger.class);
                setComp.invoke(oc, BigInteger.valueOf(0L));
            } catch (NoSuchMethodException nsme) {
                Method setComp2 = oc.getClass().getMethod("setCompositionLevel", long.class);
                setComp2.invoke(oc, 0L);
            }
        } catch (Throwable t) {
            // fallback: try to find a CompositionLevelComplexType and add it to a list if present
            try {
                Class<?> compCls = Class.forName("gov.loc.premis.v3.CompositionLevelComplexType");
                Object compInst = compCls.getDeclaredConstructor().newInstance();
                // try common setter names on CompositionLevelComplexType
                try { compCls.getMethod("setValue", BigInteger.class).invoke(compInst, BigInteger.valueOf(0L)); } catch (Throwable ignored) {}
                try {
                    Method getList = oc.getClass().getMethod("getCompositionLevel");
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> cl = (java.util.List<Object>) getList.invoke(oc);
                    cl.add(compInst);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored2) {}
        }

        // 3.b fixity
        try {
            FixityComplexType fix = new FixityComplexType();
            // set algorithm
            try { fix.setMessageDigestAlgorithm(spa(digestValue)); } catch (Throwable t) {
                try { Method m = fix.getClass().getMethod("setMessageDigestAlgorithm", StringPlusAuthority.class); m.invoke(fix, spa(digestAlgo)); } catch (Throwable ignored) {}
            }
            // set digest
            try { fix.setMessageDigest(digestValue); } catch (Throwable t) {
                try { Method m = fix.getClass().getMethod("setMessageDigest", StringPlusAuthority.class); m.invoke(fix, spa(digestValue)); } catch (Throwable ignored) {}
            }
            // attach fixity to objectCharacteristics (many bindings have getFixity() returning List<FixityComplexType>)
            try {
                oc.getFixity().add(fix);
            } catch (Throwable t) {
                // fallback: try setFixity(...)
                try {
                    Method setFix = oc.getClass().getMethod("setFixity", FixityComplexType.class);
                    setFix.invoke(oc, fix);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // ignore fixity creation problems — not fatal
        }

        // 3.c size
        try {
            // many bindings have setSize(BigInteger) on ObjectCharacteristicsComplexType
            Method setSize = oc.getClass().getMethod("setSize", BigInteger.class);
            setSize.invoke(oc, BigInteger.valueOf(sizeBytes));
        } catch (Throwable t) {
            // try file.setSize(...) as fallback
            try {
                Method setSizeOnFile = file.getClass().getMethod("setSize", BigInteger.class);
                setSizeOnFile.invoke(file, BigInteger.valueOf(sizeBytes));
            } catch (Throwable ignored) {}
            try {
                Method setSizeOnFile2 = file.getClass().getMethod("setSize", long.class);
                setSizeOnFile2.invoke(file, sizeBytes);
            } catch (Throwable ignored2) {}
        }

        // 3.d format -> formatDesignation -> formatName
        try {
            FormatComplexType fmt = new FormatComplexType();
            FormatDesignationComplexType fd = new FormatDesignationComplexType();
            // fd.getFormatName() typically returns List<String>
           try {
                // use helper spa(...) which builds StringPlusAuthority
                fd.setFormatName(spa(formatName));
            } catch (Throwable t) {
                // fallback: attempt to call setter via reflection if signature differs (unlikely here)
                try {
                    Method setFmtName = fd.getClass().getMethod("setFormatName", StringPlusAuthority.class);
                    setFmtName.invoke(fd, spa(formatName));
                } catch (Throwable ignored) {
                    LOG.fine("Could not set formatName via setter: " + ignored.getMessage());
                }
            }
            // attach fd to fmt
            try {
                fmt.getFormatDesignation().add(fd);
            } catch (Throwable ignored) {
                try {
                    Method getFmtDes = fmt.getClass().getMethod("getFormatDesignation");
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> fdlist = (java.util.List<Object>) getFmtDes.invoke(fmt);
                    fdlist.add(fd);
                } catch (Throwable ignored2) {}
            }
            // attach fmt to objectCharacteristics
            try {
                oc.getFormat().add(fmt);
            } catch (Throwable t) {
                try {
                    Method getFmtList = oc.getClass().getMethod("getFormat");
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> fmtL = (java.util.List<Object>) getFmtList.invoke(oc);
                    fmtL.add(fmt);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // ignore format creation problems
        }

        // 3.e creatingApplication
        try {
            CreatingApplicationComplexType ca = new CreatingApplicationComplexType();
            // name
            try {
                ca.getCreatingApplicationName().add(creatingAppName);
            } catch (Throwable t) {
                try {
                    Method setName = ca.getClass().getMethod("setCreatingApplicationName", String.class);
                    setName.invoke(ca, creatingAppName);
                } catch (Throwable ignored) {}
            }
            // dateCreatedByApplication
            try {
                ca.getDateCreatedByApplication().add(dateCreatedByApp);
            } catch (Throwable t) {
                try {
                    Method setDate = ca.getClass().getMethod("setDateCreatedByApplication", String.class);
                    setDate.invoke(ca, dateCreatedByApp);
                } catch (Throwable ignored) {}
            }
            // attach to objectCharacteristics
            try {
                oc.getCreatingApplication().add(ca);
            } catch (Throwable t) {
                try {
                    Method getCA = oc.getClass().getMethod("getCreatingApplication");
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> caL = (java.util.List<Object>) getCA.invoke(oc);
                    caL.add(ca);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // ignore creatingApplication problems
        }

        // finally attach objectCharacteristics to file (file.getObjectCharacteristics() usually exists and returns a list)
        try {
            file.getObjectCharacteristics().add(oc);
        } catch (Throwable t) {
            // fallback: try setObjectCharacteristics(...) or getObjectCharacteristics() via reflection
            try {
                Method getOC = file.getClass().getMethod("getObjectCharacteristics");
                @SuppressWarnings("unchecked")
                java.util.List<Object> ocList = (java.util.List<Object>) getOC.invoke(file);
                ocList.add(oc);
            } catch (Throwable ignored) {
                try {
                    Method setOC = file.getClass().getMethod("setObjectCharacteristics", ObjectCharacteristicsComplexType.class);
                    setOC.invoke(file, oc);
                } catch (Throwable ignored2) {}
            }
        }

        // 4) add file to premis root's object list
        this.premisRoot.getObject().add(file);

        LOG.info("Added file object for: " + objectIdentifierValue);

    } catch (Throwable t) {
        LOG.severe("Error while building file object (JAXB): " + t.getMessage());
    }
}


    
}