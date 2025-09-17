# premis-impl

## Overview
This project is a simple Java application built using Maven. It serves as a demonstration of a basic project structure and includes a main application class along with unit tests.

## Project Structure
```
premis-impl
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── example
│   │   │           └── App.java
│   │   └── resources
│   └── test
│       ├── java
│       │   └── com
│       │       └── example
│       │           └── AppTest.java
│       └── resources
├── pom.xml
└── README.md
```

## Requirements
- Java Development Kit (JDK) 8 or higher
- Maven 3.6 or higher

## Building the Project
To build the project, navigate to the project directory and run the following command:

```
mvn clean install
```

## Running the Application
After building the project, you can run the application using the following command:

```
mvn exec:java -Dexec.mainClass="com.example.App"
```

## Running Tests
To run the unit tests, use the following command:

```
mvn test
```

## License
This project is licensed under the MIT License. See the LICENSE file for more details.

mvn --% exec:java -Dexec.mainClass=com.example.PremisJaxbV3Generator -Dexec.args="D:/JDPS/premis-impl-project/ODHC010879122024 D:/JDPS/premis-impl-project/ODHC010879122024/odhc_premis.xml"

mvn --% exec:java -Dexec.mainClass=com.example.PremisJaxbV3Generator2 -Dexec.args="D:/JDPS/premis-impl-project/ODHC010879122024 D:/JDPS/premis-impl-project/ODHC010879122024/odhc_premis2.xml"

mvn --% exec:java -Dexec.mainClass=com.example.PremisCombinedGenerator -Dexec.args="D:/JDPS/premis-impl-project/ODHC010879122024 D:/JDPS/premis-impl-project/ODHC010879122024/odhc_premis_combined.xml"

mvn --% exec:java -Dexec.mainClass=com.example.ListPremisClasses