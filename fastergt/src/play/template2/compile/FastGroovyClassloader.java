package play.template2.compile;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilationFailedException;

public class FastGroovyClassloader extends GroovyClassLoader {
  public Class loadClass(String name, boolean lookupScriptFiles, boolean preferClassOverScript, boolean resolve) throws ClassNotFoundException, CompilationFailedException {
    if (name.startsWith("groovy.lang.GroovyObject$java") ||
      name.startsWith("play.template2.generated_templates.play.modules.gtengineplugin") ||
      name.startsWith("java.lang.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("java.util.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("groovy.lang.GroovyObject$String") ||
      name.startsWith("groovy.lang.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("groovy.lang.GroovyObject$play$template2$generated_templates$") ||
      name.startsWith("groovy.util.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("play.modules$gtengineplugin$gt_integration$") ||
      name.startsWith("play.modules.gtengineplugin$gt_integration$") ||
      name.startsWith("play.modules.gtengineplugin.gt_integration$") ||
      name.startsWith("java.net.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("java.io.play$modules$gtengineplugin$gt_integration$") ||
      name.startsWith("play.template2.generated_templates.java.") ||
      name.startsWith("java.io.java$io") ||
      name.startsWith("java.lang.java$") ||
      name.startsWith("java.net.java$") ||
      name.startsWith("java.util.java$") ||
      name.startsWith("groovy.lang.java$") ||
      name.startsWith("groovy.util.java$") ||
      name.startsWith("java.io$") ||
      name.startsWith("groovy.lang.GroovyObject$groovy$") ||
      name.endsWith("$Object") ||
      name.endsWith("$String") ||
      name.endsWith("$Map")) {
      throw new FastClassNotFoundException();
    }
    return super.loadClass(name, lookupScriptFiles, preferClassOverScript, resolve);
  }
}
