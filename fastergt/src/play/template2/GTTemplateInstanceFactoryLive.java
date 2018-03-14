package play.template2;


import org.apache.commons.io.IOUtils;
import play.template2.compile.GTCompiler;
import play.template2.compile.GTJavaCompileToClass;
import play.template2.exceptions.GTCompilationException;
import play.template2.exceptions.GTException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

import static org.apache.commons.io.IOUtils.closeQuietly;

public class GTTemplateInstanceFactoryLive extends GTTemplateInstanceFactory {

    private final Class<? extends GTJavaBase> templateClass;

    public static class CL extends ClassLoader {

        private final Set<String> classNames = new HashSet<>();
        private final Map<String, byte[]> resource2bytes = new HashMap<>();
        private final ClassLoader parent = Thread.currentThread().getContextClassLoader();

        public void add(GTJavaCompileToClass.CompiledClass[] compiledClasses) {
            for (GTJavaCompileToClass.CompiledClass cp : compiledClasses) {
                if (!classNames.contains(cp.classname)) {
                    add(cp);
                }
            }
        }

        private void add(GTJavaCompileToClass.CompiledClass cp) {
            classNames.add(cp.classname);
            defineClass(cp.classname, cp.bytes, 0, cp.bytes.length);
            String resourceName = cp.classname.replace(".", "/") + ".class";
            resource2bytes.put(resourceName, cp.bytes);
        }

        public byte[] getResourceAsBytes(String s) {
            return resource2bytes.computeIfAbsent(s, (s1) -> toByteArray(parent.getResourceAsStream(s)));
        }

        private byte[] toByteArray(InputStream is) {
            if (is == null) return null;
            try {
                return IOUtils.toByteArray(is);
            }
            catch (IOException e) {
                throw new GTCompilationException(e);
            }
            finally {
                closeQuietly(is);
            }
        }

        @Override
        public InputStream getResourceAsStream(String s) {
            if (resource2bytes.containsKey(s)) {
                return new ByteArrayInputStream(resource2bytes.get(s));
            } else {
                return parent.getResourceAsStream(s);
            }
        }

        @Override
        public Class<?> loadClass(String s) throws ClassNotFoundException {
            if (!classNames.contains(s)) return parent.loadClass(s);
            return super.loadClass(s);
        }

        @Override
        public URL getResource(String s) {
            if ( !classNames.contains(s)) return parent.getResource(s);
            return super.getResource(s);
        }

        @Override
        public Enumeration<URL> getResources(String s) throws IOException {
            if ( !classNames.contains(s)) return parent.getResources(s);
            return super.getResources(s);
        }
    }

    public GTTemplateInstanceFactoryLive(GTCompiler.CompiledTemplate compiledTemplate) {
        CL cl = new CL();
        cl.add(compiledTemplate.compiledJavaClasses);
        try {
            this.templateClass = (Class<? extends GTJavaBase>) cl.loadClass(compiledTemplate.templateClassName);
        } catch (Exception e) {
            throw new GTException("Error creating template class instance", e);
        }
    }

    @Override
    public Class<? extends GTJavaBase> getTemplateClass() {
        return templateClass;
    }
}
