package edu.pucmm.eict;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * Aplicación de ejemplo para observar el confinamiento de Snap.
 *
 * <pre>
 *   demo-java-snap                 muestra el entorno que snapd prepara
 *   demo-java-snap leer RUTA       intenta leer un archivo      (plug: home)
 *   demo-java-snap listar DIR      intenta listar un directorio (plug: home)
 *   demo-java-snap red URL         hace una petición HTTP       (plug: network)
 *   demo-java-snap servidor        servidor HTTP (lo usa el daemon demo-java-snap.servidor)
 * </pre>
 */
public class Main {

    static final String VERSION = Main.class.getPackage().getImplementationVersion() == null
            ? "desarrollo" : Main.class.getPackage().getImplementationVersion();

    public static void main(String[] args) throws Exception {
        String comando = args.length > 0 ? args[0] : "info";
        int codigo = switch (comando) {
            case "info" -> info();
            case "leer" -> leer(argumento(args));
            case "listar" -> listar(argumento(args));
            case "red" -> red(argumento(args));
            case "servidor" -> servidor();
            case "--version", "-v" -> {
                System.out.println("demo-java-snap " + VERSION);
                yield 0;
            }
            default -> {
                System.err.println("Uso: demo-java-snap [info | leer RUTA | listar DIR | red URL | servidor]");
                yield 2;
            }
        };
        if (codigo != 0) {
            System.exit(codigo);
        }
    }

    static String argumento(String[] args) {
        if (args.length < 2) {
            System.err.println("Falta el argumento para '" + args[0] + "'");
            System.exit(2);
        }
        return args[1];
    }

    // ------------------------------------------------------------------ info
    static int info() {
        System.out.println("demo-java-snap " + VERSION + "\n");
        System.out.println("Variables que define snapd:");
        for (String v : new String[]{"SNAP", "SNAP_NAME", "SNAP_VERSION", "SNAP_REVISION", "SNAP_ARCH",
                "SNAP_DATA", "SNAP_COMMON", "SNAP_USER_DATA", "SNAP_USER_COMMON", "SNAP_REAL_HOME", "HOME"}) {
            System.out.printf("  %-17s = %s%n", v, valor(System.getenv(v)));
        }
        System.out.println("\nPropiedades de la JVM:");
        System.out.printf("  %-17s = %s%n", "java.home", System.getProperty("java.home"));
        System.out.printf("  %-17s = %s%n", "java.version", System.getProperty("java.version"));
        System.out.printf("  %-17s = %s%n", "user.name", System.getProperty("user.name"));
        System.out.printf("  %-17s = %s%n", "user.home", System.getProperty("user.home"));
        System.out.printf("  %-17s = %s%n", "user.dir", System.getProperty("user.dir"));
        if (System.getenv("SNAP") == null) {
            System.out.println("\n(No se está ejecutando dentro de un snap)");
        }
        return 0;
    }

    static String valor(String v) {
        return v == null ? "(no definida)" : v;
    }

    // ------------------------------------------------------------ archivos
    static int leer(String ruta) {
        Path p = expandir(ruta);
        try (Stream<String> lineas = Files.lines(p)) {
            System.out.println("Leyendo " + p + " (primeras 10 líneas):");
            lineas.limit(10).forEach(l -> System.out.println("  | " + l));
            return 0;
        } catch (IOException | SecurityException e) {
            return denegado(p, e);
        } catch (java.io.UncheckedIOException e) {
            return denegado(p, e.getCause());
        }
    }

    static int listar(String dir) {
        Path p = expandir(dir);
        try (Stream<Path> hijos = Files.list(p)) {
            System.out.println("Contenido de " + p + ":");
            hijos.sorted().limit(30).forEach(h -> System.out.println("  " + h.getFileName()));
            return 0;
        } catch (IOException | SecurityException e) {
            return denegado(p, e);
        }
    }

    /** "~" se expande al HOME real del usuario, no al HOME que snapd redirige. */
    static Path expandir(String ruta) {
        if (ruta.startsWith("~")) {
            String home = System.getenv("SNAP_REAL_HOME");
            if (home == null) home = System.getProperty("user.home");
            ruta = home + ruta.substring(1);
        }
        return Paths.get(ruta);
    }

    static int denegado(Path p, Throwable e) {
        if (!(e instanceof java.nio.file.AccessDeniedException) && !(e instanceof SecurityException)) {
            System.err.println("ERROR al acceder a " + p + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
        System.err.println("ACCESO DENEGADO a " + p);
        System.err.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
        System.err.println("  Revise los plugs con: snap connections demo-java-snap");
        System.err.println("  y los rechazos de AppArmor con: sudo journalctl -k | grep -i 'apparmor=\"DENIED\"'");
        return 1;
    }

    // ------------------------------------------------------------------ red
    static int red(String url) {
        try {
            HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = cliente.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String cuerpo = r.body();
            System.out.println("HTTP " + r.statusCode() + " desde " + url);
            System.out.println(cuerpo.substring(0, Math.min(300, cuerpo.length())));
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR DE RED hacia " + url + ": " + e);
            System.err.println("  ¿Está conectado el plug 'network'? snap connections demo-java-snap");
            return 1;
        }
    }

    // ------------------------------------------------------------- servidor
    static int servidor() throws IOException {
        int puerto = Integer.parseInt(env("PUERTO", "8085"));
        String mensaje = env("MENSAJE", "Hola Mundo desde un snap :-D");
        // Daemon: corre como root; SNAP_DATA = /var/snap/demo-java-snap/<revisión>
        Path archivo = Paths.get(env("SNAP_DATA", System.getProperty("java.io.tmpdir"))).resolve("visitas.txt");

        HttpServer srv = HttpServer.create(new InetSocketAddress(puerto), 0);
        srv.createContext("/", ex -> {
            long visitas = incrementar(archivo);
            String cuerpo = """
                    %s
                    Versión  : %s (revisión %s)
                    Usuario  : %s
                    Java     : %s
                    Datos    : %s
                    Visitas  : %d
                    Fecha    : %s
                    """.formatted(mensaje, VERSION, valor(System.getenv("SNAP_REVISION")),
                    System.getProperty("user.name"), System.getProperty("java.home"),
                    archivo, visitas, LocalDateTime.now());
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Deteniendo servidor...");
            srv.stop(1);
        }));
        srv.start();
        System.out.printf("demo-java-snap %s escuchando en el puerto %d (datos en %s)%n", VERSION, puerto, archivo);
        return 0;
    }

    static synchronized long incrementar(Path archivo) {
        long n = 0;
        try {
            if (Files.exists(archivo)) n = Long.parseLong(Files.readString(archivo).trim());
            Files.writeString(archivo, Long.toString(++n));
        } catch (IOException | NumberFormatException e) {
            System.err.println("No se pudo actualizar " + archivo + ": " + e.getMessage());
        }
        return n;
    }

    static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return v == null || v.isBlank() ? porDefecto : v;
    }
}
