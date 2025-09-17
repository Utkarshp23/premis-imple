package com.example;

import gov.loc.premis.v3.*;
import javax.xml.bind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PremisXmlGenerator {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: PremisXmlGenerator <input-folder> <output-xml>");
            return;
        }
        Path inputDir = Paths.get(args[0]);
        File outputFile = new File(args[1]);
        PremisXmlGenerator generator = new PremisXmlGenerator();
        PremisType premis = generator.buildPremis(inputDir);
        generator.writePremisXml(premis, outputFile);
        System.out.println("PREMIS XML generated: " + outputFile.getAbsolutePath());
    }

    public PremisType buildPremis(Path inputDir) throws IOException {
        PremisType premis = new PremisType();

        // Intellectual Object
        PremisType.IntellectualObject intellectualObject = new PremisType.IntellectualObject();
        ObjectIdentifierType objId = new ObjectIdentifierType();
        objId.setObjectIdentifierType("CNR");
        objId.setObjectIdentifierValue(inputDir.getFileName().toString());
        intellectualObject.setObjectIdentifier(objId);

        ObjectCharacteristicsType objChar = new ObjectCharacteristicsType();
        objChar.setCompositionLevel("1");
        intellectualObject.setObjectCharacteristics(objChar);

        intellectualObject.getSignificantProperties().add("Case SIP");
        premis.getIntellectualObject().add(intellectualObject);

        // Agents
        premis.getAgent().add(createAgent("system", "JTDR", "JTDR", "software"));
        premis.getAgent().add(createAgent("depositor", "uploader@example.org", "Case Uploader", "human"));

        // Rights
        RightsType rights = new RightsType();
        RightsStatementType rightsStatement = new RightsStatementType();
        rightsStatement.setRightsBasis("statute");
        rightsStatement.getRightsGranted().add("Access restricted to authorized user");
        rights.getRightsStatement().add(rightsStatement);
        premis.getRights().add(rights);

        // File objects: metadata XML
        Path metadataDir = inputDir.resolve("data/metadata");
        if (Files.exists(metadataDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(metadataDir, "*.xml")) {
                for (Path file : stream) {
                    premis.getObject().add(createFileObject(file, "XML", "JTDR", "2024-12-07T00:00:00+05:30"));
                }
            }
        }

        // rep1 - originals
        Path rep1Dir = inputDir.resolve("representation/rep1");
        if (Files.exists(rep1Dir)) {
            int idx = 1;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rep1Dir, "*.pdf")) {
                for (Path file : stream) {
                    String dateCreated = "2024-12-07T00:00:00+05:30";
                    premis.getObject().add(createFileObject(file, "PDF", "JTDR", dateCreated));
                    idx++;
                }
            }
        }

        // rep2 - converted (empty objects as in eg.xml)
        for (int i = 0; i < 2; i++) {
            ObjectType rep2Obj = new ObjectType();
            rep2Obj.setObjectIdentifier(new ObjectIdentifierType());
            rep2Obj.setObjectCharacteristics(new ObjectCharacteristicsType());
            rep2Obj.setCreatingApplication(new CreatingApplicationType());
            rep2Obj.getRelationship().add(new RelationshipType());
            premis.getObject().add(rep2Obj);
        }

        // schema (empty object as in eg.xml)
        ObjectType schemaObj = new ObjectType();
        schemaObj.setObjectIdentifier(new ObjectIdentifierType());
        schemaObj.setObjectCharacteristics(new ObjectCharacteristicsType());
        premis.getObject().add(schemaObj);

        // Events - conversion events (example for two files)
        premis.getEvent().add(createConversionEvent(
                "2025-09-16T10:10:00+05:30",
                "Converted rep1/data/ODHC012342025_1_original.pdf → rep2/data/ODHC012342025_1_converted.pdf using PDFConv v2.1"
        ));
        premis.getEvent().add(createConversionEvent(
                "2025-09-16T10:12:00+05:30",
                "Converted rep1/data/ODHC012342025_2_original.pdf → rep2/data/ODHC012342025_2_converted.pdf using PDFConv v2.1"
        ));

        // Relationships (empty as in eg.xml)
        RelationshipType rel = new RelationshipType();
        for (int i = 0; i < 4; i++) {
            rel.getRelationshipElement().add(new RelationshipElementType());
        }
        premis.getRelationship().add(rel);

        return premis;
    }

    private AgentType createAgent(String idType, String idValue, String name, String type) {
        AgentType agent = new AgentType();
        AgentIdentifierType agentId = new AgentIdentifierType();
        agentId.setAgentIdentifierType(idType);
        agentId.setAgentIdentifierValue(idValue);
        agent.setAgentIdentifier(agentId);
        agent.setAgentName(name);
        agent.setAgentType(type);
        return agent;
    }

    private ObjectType createFileObject(Path file, String formatName, String appName, String dateCreated) throws IOException {
        ObjectType obj = new ObjectType();
        ObjectIdentifierType objId = new ObjectIdentifierType();
        objId.setObjectIdentifierType("FilePath");
        objId.setObjectIdentifierValue(file.toString().replace("\\", "/"));
        obj.setObjectIdentifier(objId);

        ObjectCharacteristicsType characteristics = new ObjectCharacteristicsType();
        characteristics.setCompositionLevel("0");
        characteristics.setSize(Files.size(file));
        FixityType fixity = new FixityType();
        characteristics.getFixity().add(fixity);
        FormatType format = new FormatType();
        FormatDesignationType formatDesignation = new FormatDesignationType();
        formatDesignation.setFormatName(formatName);
        format.setFormatDesignation(formatDesignation);
        characteristics.setFormat(format);
        obj.setObjectCharacteristics(characteristics);

        CreatingApplicationType creatingApp = new CreatingApplicationType();
        creatingApp.setCreatingApplicationName(appName);
        creatingApp.setDateCreatedByApplication(dateCreated);
        obj.setCreatingApplication(creatingApp);

        return obj;
    }

    private EventType createConversionEvent(String dateTime, String detail) {
        EventType event = new EventType();
        event.setEventIdentifier(new EventIdentifierType());
        event.setEventType("format conversion");
        event.setEventDateTime(dateTime);
        EventDetailInformationType detailInfo = new EventDetailInformationType();
        detailInfo.setEventDetail(detail);
        event.setEventDetailInformation(detailInfo);
        EventOutcomeInformationType outcomeInfo = new EventOutcomeInformationType();
        outcomeInfo.setEventOutcome("success");
        event.setEventOutcomeInformation(outcomeInfo);
        event.getLinkingAgentIdentifier().add(new LinkingAgentIdentifierType());
        event.getLinkingObjectIdentifier().add(new LinkingObjectIdentifierType());
        event.getLinkingObjectIdentifier().add(new LinkingObjectIdentifierType());
        return event;
    }

    public void writePremisXml(PremisType premis, File outputFile) throws Exception {
        JAXBContext context = JAXBContext.newInstance(PremisType.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.marshal(premis, outputFile);
    }
}