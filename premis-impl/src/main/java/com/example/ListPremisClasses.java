package com.example;

import java.io.*;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.nio.file.*;
import java.util.*;

public class ListPremisClasses {
    public static void main(String[] args) throws Exception {
        String pkgPath = "gov/loc/premis/v3/";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = cl.getResources(pkgPath);
        Set<String> found = new TreeSet<>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if ("file".equals(url.getProtocol())) {
                Path dir = Paths.get(url.toURI());
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.class")) {
                    for (Path p : ds) found.add(p.getFileName().toString());
                }
            } else if ("jar".equals(url.getProtocol())) {
                String s = url.toString();
                String jarPath = s.substring(s.indexOf("file:") + 5, s.indexOf("!"));
                try (JarFile jf = new JarFile(jarPath)) {
                    Enumeration<JarEntry> en = jf.entries();
                    while (en.hasMoreElements()) {
                        JarEntry je = en.nextElement();
                        String name = je.getName();
                        if (name.startsWith(pkgPath) && name.endsWith(".class")) found.add(name.substring(pkgPath.length()));
                    }
                }
            }
        }
        if (found.isEmpty()) {
            System.out.println("No classes found under " + pkgPath + ". Did you run mvn compile?");
            return;
        }
        System.out.println("Classes found in gov.loc.premis.v3:");
        for (String s : found) System.out.println("  gov.loc.premis.v3." + s.replace(".class",""));
    }
}
