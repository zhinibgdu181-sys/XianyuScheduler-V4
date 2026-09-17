import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
public class ParseAll {
 public static void main(String[] args)throws Exception {
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> errors=new DiagnosticCollector<>();
  try(StandardJavaFileManager fm=compiler.getStandardFileManager(errors,null,null)) {
   List<java.io.File> files=new ArrayList<>();
   try(var paths=Files.walk(Path.of(args[0]))){paths.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile()));}
   JavacTask task=(JavacTask)compiler.getTask(null,fm,errors,List.of("-proc:none"),null,fm.getJavaFileObjectsFromFiles(files));
   task.parse();
   for(var d:errors.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
   System.out.println("PARSE PASS "+files.size()+" production Java files (syntax only)");
  }
 }
}
