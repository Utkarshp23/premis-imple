package com.example.validator;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ErrorHandler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class XmlValidator {

    public static class CollectingErrorHandler implements ErrorHandler {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void warning(SAXParseException exception) {
            errors.add("WARNING: " + format(exception));
        }

        @Override
        public void error(SAXParseException exception) {
            errors.add("ERROR: " + format(exception));
        }

        @Override
        public void fatalError(SAXParseException exception) {
            errors.add("FATAL: " + format(exception));
        }

        private String format(SAXParseException e) {
            return String.format("line %d, col %d: %s", e.getLineNumber(), e.getColumnNumber(), e.getMessage());
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    public static List<String> validate(File xml, File xsd) throws SAXException, IOException {
        SchemaFactory factory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
        Schema schema = factory.newSchema(xsd);               // or factory.newSchema(new Source[]{...});
        Validator validator = schema.newValidator();

        CollectingErrorHandler handler = new CollectingErrorHandler();
        validator.setErrorHandler(handler);

        validator.validate(new StreamSource(xml));

        return handler.getErrors();
    }

    public static void main(String[] args) throws Exception {
        File xml = new File("C:\\Users\\91898\\Downloads\\eg_corrected4.xml");
        File xsd = new File("D:\\JDPS\\premis-impl-project\\premis-impl\\src\\main\\resources\\premis.xsd");
        List<String> errors = validate(xml, xsd);
        if (errors.isEmpty()) {
            System.out.println("Valid!");
        } else {
            System.out.println("Invalid. Issues:");
            errors.forEach(System.out::println);
        }
    }
}
