package org.apache.openejb.assembler.classic;

import org.apache.openejb.config.event.BeforeDeploymentEvent;
import org.apache.openejb.config.sys.ListAdapter;
import org.apache.openejb.config.sys.ServiceProvider;
import org.apache.openejb.loader.Files;
import org.apache.openejb.observer.Observes;
import org.apache.openejb.util.JarExtractor;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.URLs;
import org.xml.sax.Attributes;
import org.xml.sax.HandlerBase;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class DeployTimeEnhancer {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB_DEPLOY, DeployTimeEnhancer.class);
    private static final SAXParserFactory SAX_PARSER_FACTORY = SAXParserFactory.newInstance();

    private static final String CLASS_EXT = ".class";
    private static final String PROPERTIES_FILE_PROP = "propertiesFile";
    private static final String META_INF_PERSISTENCE_XML = "META-INF/persistence.xml";

    static {
        SAX_PARSER_FACTORY.setNamespaceAware(true);
        SAX_PARSER_FACTORY.setValidating(false);
    }

    private final Method enhancerMethod;
    private final Constructor<?> optionsConstructor;

    public DeployTimeEnhancer() {
        Method mtd;
        Constructor<?> cstr;
        final ClassLoader cl = DeployTimeEnhancer.class.getClassLoader();
        try {
            final Class<?> enhancerClass = cl.loadClass("org.apache.openjpa.enhance.PCEnhancer");
            final Class<?> arg2 = cl.loadClass("org.apache.openjpa.lib.util.Options");
            cstr = arg2.getConstructor(Properties.class);
            mtd = enhancerClass.getMethod("run", String[].class, arg2);
        } catch (Exception e) {
            LOGGER.warning("openjpa enhancer can't be found in the contanier, will be skipped");
            mtd = null;
            cstr = null;
        }
        optionsConstructor = cstr;
        enhancerMethod = mtd;
    }

    public void enhance(@Observes final BeforeDeploymentEvent event) {
        if (enhancerMethod == null) {
            LOGGER.debug("OpenJPA is not available so no deploy-time enhancement will be done");
            return;
        }

        // find persistence.xml
        final Map<String, List<String>> classesByPXml = new HashMap<String, List<String>>();
        for (URL url : event.getUrls()) {
            final File file = URLs.toFile(url);
            if (file.isDirectory()) {
                final String pXmls = getWarPersistenceXml(url);
                if (pXmls != null) {
                    feed(classesByPXml, pXmls);
                }
            } else if (file.getName().endsWith(".jar")) {
                try {
                    final JarFile jar = new JarFile(file);
                    ZipEntry entry = jar.getEntry(META_INF_PERSISTENCE_XML);
                    if (entry != null) {
                        final String path = file.getAbsolutePath();
                        final File unpacked = new File(path.substring(0, path.length() - 4));
                        JarExtractor.extract(file, unpacked);
                        feed(classesByPXml, new File(unpacked, META_INF_PERSISTENCE_XML).getAbsolutePath());
                    }
                } catch (IOException e) {
                    // ignored
                }
            }
        }

        // enhancement
        for (Map.Entry<String, List<String>> entry : classesByPXml.entrySet()) {
            final Properties opts = new Properties();
            opts.setProperty(PROPERTIES_FILE_PROP, entry.getKey());

            final Object optsArg;
            try {
                optsArg = optionsConstructor.newInstance(opts);
            } catch (Exception e) {
                LOGGER.debug("can't create options for enhancing");
                return;
            }

            LOGGER.info("enhancing url(s): " + Arrays.asList(event.getUrls()));
            try {
                enhancerMethod.invoke(null, toFilePaths(entry.getValue()), optsArg);
            } catch (Exception e) {
                LOGGER.warning("can't enhanced at deploy-time entities", e);
            }
        }

        // clean up extracted jars
        for (Map.Entry<String, List<String>> entry : classesByPXml.entrySet()) {
            final List<String> values = entry.getValue();
            for (String path : values) {
                final File file = new File(path + ".jar");
                if (file.exists()) {
                    Files.delete(file);
                }
            }
            values.clear();
        }

        classesByPXml.clear();
    }

    private void feed(final Map<String, List<String>> classesByPXml, final String pXml) {
        final List<String> paths = new ArrayList<String>();

        // first add the classes directory where is the persistence.xml
        if (pXml.endsWith(META_INF_PERSISTENCE_XML)) {
            paths.add(pXml.substring(0, pXml.length() - META_INF_PERSISTENCE_XML.length()));
        } else if (pXml.endsWith("/WEB-INF/persistence.xml")) {
            paths.add(pXml.substring(0, pXml.length() - 24));
        }

        // then jar-file
        try {
            final SAXParser parser = SAX_PARSER_FACTORY.newSAXParser();
            final JarFileParser handler = new JarFileParser();
            parser.parse(new File(pXml), handler);
            for (String path : handler.getPaths()) {
                paths.add(relative(paths.iterator().next(), path));
            }
        } catch (Exception e) {
            LOGGER.error("can't parse '" + pXml + "'", e);
        }

        classesByPXml.put(pXml, paths);
    }

    // relativePath = relative path to the jar file containing the persistence.xml
    private String relative(final String relativePath, final String pXmlPath) {
        return new File(new File(pXmlPath).getParent(), relativePath).getAbsolutePath();
    }

    private String getWarPersistenceXml(final URL url) {
        final File dir = URLs.toFile(url);
        if (dir.isDirectory() && dir.getAbsolutePath().endsWith("/WEB-INF/classes")) {
            final File pXmlStd = new File(dir.getParentFile(), "persistence.xml");
            if (pXmlStd.exists()) {
                return  pXmlStd.getAbsolutePath();
            }

            final File pXml = new File(dir, META_INF_PERSISTENCE_XML);
            if (pXml.exists()) {
                return pXml.getAbsolutePath();
            }
        }
        return null;
    }

    private String[] toFilePaths(final List<String> urls) {
        final List<String> files = new ArrayList<String>();
        for (String url : urls) {
            final File dir = new File(url);
            if (!dir.isDirectory()) {
                continue;
            }

            for (File f : Files.collect(dir, new ClassFilter())) {
                files.add(f.getAbsolutePath());
            }
        }
        return files.toArray(new String[files.size()]);
    }

    private static class ClassFilter implements FileFilter {
        @Override
        public boolean accept(final File file) {
            return file.getName().endsWith(CLASS_EXT);
        }
    }

    private static class JarFileParser extends DefaultHandler {
        private final List<String> paths = new ArrayList<String>();
        private boolean getIt = false;

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes att) throws SAXException {
            if (!localName.endsWith("jar-file")) {
                return;
            }

            getIt = true;
        }

        @Override
        public void characters(final char ch[], final int start, final int length) throws SAXException {
            if (getIt) {
                paths.add(new StringBuilder().append(ch, start, length).toString());
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            getIt = false;
        }

        public List<String> getPaths() {
            return paths;
        }
    }
}
