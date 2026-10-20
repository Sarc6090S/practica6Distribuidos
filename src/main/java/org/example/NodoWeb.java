package org.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

class Info {
    int id, min, max;
    Info(int id, int min, int max) {
        this.id = id;
        this.min = min;
        this.max = max;
    }
}

class InfoPrimos implements Runnable {
    Info info;
    int[] resultados;
    StringBuilder primosList;

    InfoPrimos(Info info, int[] resultados, StringBuilder primosList) {
        this.info = info;
        this.resultados = resultados;
        this.primosList = primosList;
    }

    static boolean esPrimo(int n) {
        if (n == 0 || n == 1 || n == 4) return false;
        for (int i = 2; i < n / 2; i++)
            if (n % i == 0) return false;
        return true;
    }

    @Override
    public void run() {
        int suma = 0, total = 0;
        StringBuilder found = new StringBuilder("- Los numeros primos encontrados en el rango son: ");
        for (int i = info.min; i <= info.max; i++) {
            if (esPrimo(i)) {
                suma += i;
                total++;
                found.append(i).append(" ");
            }
        }
        synchronized (resultados) {
            resultados[0] += suma;
            resultados[1] += total;
            primosList.append("\n-------------------------------------------------\n");
            primosList.append("- Thread id: ").append(info.id).append("\n");
            primosList.append("- Limite inferior: ").append(info.min).append("\n");
            primosList.append("- Limite superior : ").append(info.max).append("\n");
            primosList.append("- \n");
            primosList.append(found).append("\n");
            primosList.append("- Sumatoria de todos los numeros primos encontrados: ").append(suma).append("\n");
            primosList.append("- Total de numeros primos encontrados en el rango: ").append(total).append("\n");
            primosList.append("- Total de numeros primos encontrados en total: ").append(resultados[1]).append("\n");
            primosList.append("-------------------------------------------------\n");
        }
    }
}

class DatosPar {
    String url;
    int id;
    DatosPar(String url, int id) {
        this.url = url;
        this.id = id;
    }
}

class Cola {
    Queue<Object[]> fila = new LinkedList<>();
    int encolados = 0, atendidos = 0;
    String tag;

    Cola(String tag) { this.tag = tag; }

    synchronized void agregar(Object[] s) {
        fila.add(s);
        encolados++;
        System.out.println("[" + tag + " COLA] #" + s[0] + " encolada. Espera: " + fila.size());
        notifyAll();
    }

    synchronized Object[] siguiente() throws InterruptedException {
        while (fila.isEmpty()) wait();
        Object[] s = fila.poll();
        atendidos++;
        System.out.println("[" + tag + " COLA] #" + s[0] + " desencolada. Restantes: " + fila.size());
        return s;
    }

    synchronized String estado() {
        return "Espera: " + fila.size() + " | Atendidos: " + atendidos + "/" + encolados;
    }
}

class Despachador implements Runnable {
    Cola cola;
    int nodoId;
    Semaphore semaforo;
    DatosPar[] pares;
    AtomicInteger rrIndex;

    Despachador(Cola cola, int nodoId, Semaphore semaforo, DatosPar[] pares, AtomicInteger rrIndex) {
        this.cola = cola;
        this.nodoId = nodoId;
        this.semaforo = semaforo;
        this.pares = pares;
        this.rrIndex = rrIndex;
    }

    @Override
    public void run() {
        while (true) {
            try {
                Object[] datos = cola.siguiente();
                int id = (int) datos[0];
                int boundary = (int) datos[1];
                int threadss = (int) datos[2];
                String tipo = (String) datos[3];
                int origenId = (int) datos[4];
                String[] res = (String[]) datos[5];

                System.out.println("[NODO " + nodoId + " DESPACHADOR] #" + id + " | Semaforo: " + semaforo.availablePermits());

                new Thread(() -> {
                    String r;
                    if (tipo.equals("CLIENTE"))
                        r = atenderCliente(boundary, threadss, id);
                    else
                        r = atenderServicio(boundary, threadss, id, origenId);
                    synchronized (res) {
                        res[0] = r;
                        res.notifyAll();
                    }
                }).start();
            } catch (InterruptedException e) { break; }
        }
    }

    String atenderCliente(int boundary, int threadss, int solicitudId) {
        String t = "[NODO " + nodoId + "]";
        System.out.println("\n" + t + " ========== SOLICITUD CLIENTE #" + solicitudId + " ==========");
        System.out.println(t + " Limite: " + boundary + " | Hilos: " + threadss);

        if (semaforo.tryAcquire()) {
            System.out.println(t + " Recurso OK, procesando local. Semaforo: " + semaforo.availablePermits());
            try {
                String resultado = hacerCalculo(boundary, threadss, t);
                return "{\"ok\":true,\"nodo\":" + nodoId + ",\"tipo\":\"local\"," + extraerJson(resultado) + "}";
            } finally {
                semaforo.release();
                System.out.println(t + " Liberado. Semaforo: " + semaforo.availablePermits());
                System.out.println(t + " ========== FIN #" + solicitudId + " ==========");
            }
        }

        System.out.println(t + " *** SEMAFORO EN 0 -> DELEGANDO ***");
        String resultado = delegarAPar(boundary, threadss, t);
        System.out.println(t + " *** ROL RESTAURADO ***");
        System.out.println(t + " ========== FIN #" + solicitudId + " ==========");
        return resultado;
    }

    String atenderServicio(int boundary, int threadss, int solicitudId, int origenId) {
        String t = "[NODO " + nodoId + "]";
        System.out.println("\n" + t + " ========== SERVICIO de NODO " + origenId + " #" + solicitudId + " ==========");

        try { semaforo.acquire(); } catch (InterruptedException e) {
            return "{\"ok\":false,\"error\":\"interrumpido\"}";
        }

        System.out.println(t + " Recurso para NODO " + origenId);
        try {
            String resultado = hacerCalculo(boundary, threadss, t);
            return "{\"ok\":true,\"nodo\":" + nodoId + ",\"tipo\":\"servicio\"," + extraerJson(resultado) + "}";
        } finally {
            semaforo.release();
            System.out.println(t + " Liberado tras servicio. Semaforo: " + semaforo.availablePermits());
        }
    }

    String delegarAPar(int boundary, int threadss, String t) {
        for (int i = 0; i < pares.length; i++) {
            int idx = rrIndex.getAndIncrement() % pares.length;
            DatosPar par = pares[idx];
            System.out.println(t + " Intentando NODO " + par.id + " (" + par.url + ")...");

            try {
                String body = "{\"limite\":" + boundary + ",\"hilos\":" + threadss + ",\"nodoOrigen\":" + nodoId + "}";
                String resp = httpPost(par.url + "/api/servicio", body);
                System.out.println(t + " NODO " + par.id + " respondio OK");

                return resp.substring(0, resp.length() - 1)
                        + ",\"delegadoPor\":" + nodoId
                        + ",\"procesadoPor\":" + par.id
                        + ",\"esquema\":\"delegacion HTTP REST\"}";
            } catch (Exception e) {
                System.out.println(t + " NODO " + par.id + " no disponible: " + e.getMessage());
                if (i < pares.length - 1) {
                    int sig = rrIndex.get() % pares.length;
                    System.out.println(t + " Failover -> NODO " + pares[sig].id);
                }
            }
        }

        System.out.println(t + " Ningun par disponible, esperando local...");
        try { semaforo.acquire(); } catch (InterruptedException e) {
            return "{\"ok\":false,\"error\":\"interrumpido\"}";
        }
        try {
            String resultado = hacerCalculo(boundary, threadss, t);
            return "{\"ok\":true,\"nodo\":" + nodoId + ",\"tipo\":\"fallback_local\"," + extraerJson(resultado) + "}";
        } finally {
            semaforo.release();
        }
    }

    String hacerCalculo(int boundary, int threadss, String t) {
        if (boundary <= 1) return "ERROR:NO SE ACEPTAN NUMEROS NEGATIVOS O 1";
        if (threadss < 1) return "ERROR:NO SE ACEPTA MENOS DE 1 HILO";
        if (threadss > boundary) return "ERROR:MAS HILOS QUE NUMEROS (" + threadss + ">" + boundary + ")";

        System.out.println(t + " Procesando... (Thread.sleep 3s)");
        try { Thread.sleep(3000); } catch (InterruptedException e) { }

        int[] resultados = new int[2];
        StringBuilder primosList = new StringBuilder();
        int rango = boundary / threadss;
        Thread[] hilos = new Thread[threadss];
        int inicio = 1;

        for (int i = 0; i < threadss; i++) {
            int min = inicio;
            int max;
            inicio += rango - 1;
            if (i == threadss - 1 && inicio < boundary) max = boundary;
            else if (inicio > boundary) max = boundary;
            else max = inicio;
            inicio++;
            hilos[i] = new Thread(new InfoPrimos(new Info(i, min, max), resultados, primosList));
            hilos[i].start();
        }

        for (int i = 0; i < threadss; i++) {
            try { hilos[i].join(); } catch (InterruptedException e) { }
        }

        System.out.println(t + " Primos: " + resultados[1] + " | Suma: " + resultados[0]);
        return resultados[0] + "|" + resultados[1] + "|" + primosList.toString();
    }

    String extraerJson(String calc) {
        if (calc.startsWith("ERROR:"))
            return "\"ok\":false,\"error\":\"" + escJson(calc.substring(6)) + "\"";
        String[] partes = calc.split("\\|", 3);
        return "\"suma\":" + partes[0] + ",\"totalPrimos\":" + partes[1]
                + ",\"detalle\":\"" + escJson(partes[2]) + "\"";
    }

    static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String httpPost(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        con.setConnectTimeout(5000);
        con.setReadTimeout(120000);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String linea;
        while ((linea = br.readLine()) != null) sb.append(linea);
        br.close();
        con.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + ": " + sb);
        return sb.toString();
    }
}

public class NodoWeb {
    static int nodoId;
    static Cola cola;
    static Semaphore semaforo;
    static AtomicInteger contador = new AtomicInteger(0);
    static DatosPar[] pares;
    static String htmlCache = null;

    public static void main(String[] args) throws Exception {
        int puerto = Integer.parseInt(getEnvOrArg(args, 0, "PORT", "8080"));
        nodoId = Integer.parseInt(getEnvOrArg(args, 1, "NODO_ID", "1"));
        String paresStr = getEnvOrArg(args, 2, "PARES", "");

        List<DatosPar> lista = new ArrayList<>();
        if (!paresStr.isEmpty()) {
            for (String p : paresStr.split(",")) {
                String[] parts = p.split("\\|");
                lista.add(new DatosPar(parts[0].trim(), Integer.parseInt(parts[1].trim())));
            }
        }
        pares = lista.toArray(new DatosPar[0]);

        semaforo = new Semaphore(1, true);
        cola = new Cola("NODO " + nodoId);
        AtomicInteger rrIndex = new AtomicInteger(0);

        System.out.println("*****************************************************************");
        System.out.println("*****     Practica 6 - Servicio Web (REST API)             ******");
        System.out.println("*****   NODO " + nodoId + " - Puerto " + puerto + "                              *****");
        System.out.println("*****  Autor: Moreno Zarate Israel            Grupo: 7CM1   *****");
        System.out.println("*****************************************************************\n");

        System.out.println("[NODO " + nodoId + "] Puerto HTTP: " + puerto);
        System.out.println("[NODO " + nodoId + "] Semaforo: 1 recurso");
        System.out.println("[NODO " + nodoId + "] Cola FIFO + Despachador");
        System.out.println("[NODO " + nodoId + "] Pares (" + pares.length + "):");
        for (DatosPar par : pares)
            System.out.println("    -> NODO " + par.id + ": " + par.url);

        Thread desp = new Thread(new Despachador(cola, nodoId, semaforo, pares, rrIndex));
        desp.setDaemon(true);
        desp.start();

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/") || ex.getRequestURI().getPath().equals("/index.html")) {
                String html = cargarHtml();
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.getResponseBody().close();
            } else {
                String resp = "{\"error\":\"no encontrado\"}";
                enviarJson(ex, 404, resp);
            }
        });

        server.createContext("/api/primos", ex -> {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,OPTIONS");
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }

            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
            int limite, hilos;
            try {
                limite = Integer.parseInt(params.getOrDefault("limite", "0"));
                hilos = Integer.parseInt(params.getOrDefault("hilos", "1"));
            } catch (NumberFormatException e) {
                enviarJson(ex, 400, "{\"ok\":false,\"error\":\"parametros invalidos\"}");
                return;
            }

            if (limite <= 0 || hilos <= 0) {
                enviarJson(ex, 400, "{\"ok\":false,\"error\":\"limite e hilos deben ser positivos\"}");
                return;
            }

            int id = contador.incrementAndGet();
            System.out.println("\n[NODO " + nodoId + "] GET /api/primos?limite=" + limite + "&hilos=" + hilos + " -> #" + id);

            String[] res = {null};
            cola.agregar(new Object[]{id, limite, hilos, "CLIENTE", 0, res});

            synchronized (res) {
                while (res[0] == null) {
                    try { res.wait(); } catch (InterruptedException e) { break; }
                }
            }

            enviarJson(ex, 200, res[0]);
        });

        server.createContext("/api/servicio", ex -> {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST,OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int limite = extraerInt(body, "limite");
            int hilos = extraerInt(body, "hilos");
            int origen = extraerInt(body, "nodoOrigen");

            int id = contador.incrementAndGet();
            System.out.println("\n[NODO " + nodoId + "] POST /api/servicio de NODO " + origen + " -> #" + id);

            String[] res = {null};
            cola.agregar(new Object[]{id, limite, hilos, "SERVICIO", origen, res});

            synchronized (res) {
                while (res[0] == null) {
                    try { res.wait(); } catch (InterruptedException e) { break; }
                }
            }

            enviarJson(ex, 200, res[0]);
        });

        server.createContext("/api/estado", ex -> {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            String json = "{\"nodo\":" + nodoId
                    + ",\"semaforo\":" + semaforo.availablePermits()
                    + ",\"cola\":\"" + Despachador.escJson(cola.estado()) + "\""
                    + ",\"pares\":" + pares.length + "}";
            enviarJson(ex, 200, json);
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(20));
        server.start();

        System.out.println("\n[NODO " + nodoId + "] Servidor HTTP listo en puerto " + puerto);
        System.out.println("[NODO " + nodoId + "] Endpoints:");
        System.out.println("    GET  /                          -> Pagina web cliente");
        System.out.println("    GET  /api/primos?limite=N&hilos=N -> Calcular primos");
        System.out.println("    POST /api/servicio              -> Servicio inter-nodo");
        System.out.println("    GET  /api/estado                -> Estado del nodo\n");
    }

    static void enviarJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    static int extraerInt(String json, String key) {
        String buscar = "\"" + key + "\":";
        int idx = json.indexOf(buscar);
        if (idx < 0) return 0;
        idx += buscar.length();
        StringBuilder num = new StringBuilder();
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) {
            num.append(json.charAt(idx));
            idx++;
        }
        return Integer.parseInt(num.toString());
    }

    static String getEnvOrArg(String[] args, int idx, String envKey, String defaultVal) {
        String env = System.getenv(envKey);
        if (env != null && !env.isEmpty()) return env;
        if (args.length > idx) return args[idx];
        return defaultVal;
    }

    static String cargarHtml() {
        if (htmlCache != null) return htmlCache;
        try (InputStream is = NodoWeb.class.getResourceAsStream("/index.html")) {
            if (is != null) {
                htmlCache = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return htmlCache;
            }
        } catch (Exception e) { }
        try {
            java.nio.file.Path p = java.nio.file.Path.of("index.html");
            if (java.nio.file.Files.exists(p)) {
                htmlCache = java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
                return htmlCache;
            }
        } catch (Exception e) { }
        htmlCache = "<html><body><h1>Nodo " + nodoId + "</h1><p>index.html no encontrado</p></body></html>";
        return htmlCache;
    }
}
