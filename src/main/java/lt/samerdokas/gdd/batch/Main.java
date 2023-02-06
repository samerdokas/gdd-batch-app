package lt.samerdokas.gdd.batch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class Main {
    private static final boolean USE_UNICODE = Env.isTerminalCompatible();
    private static final ScheduledExecutorService TERMINATOR = Executors.newSingleThreadScheduledExecutor((r) -> new Thread(r, "JDK-8208693"));
    
    private static Set<String> readFile(String relPath) {
        try {
            return Files.readAllLines(Paths.get(relPath), StandardCharsets.UTF_8).stream().filter(Predicate.not(String::isBlank)).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            return Collections.emptySet();
        }
    }
    
    private static JsonObject getContent(HttpResponse<byte[]> response) {
        Optional<String> encoding = response.headers().firstValue("Content-Encoding");
        byte[] body;
        if (encoding.isPresent() && "gzip".equals(encoding.get())) {
            try {
                GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()));
                body = in.readAllBytes();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        } else {
            body = response.body();
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    
    private static List<Page> toPages(JsonObject wrapper) {
        String item = wrapper.get("_").getAsString();
        String title = wrapper.get("title").getAsString();
        JsonObject pages = wrapper.getAsJsonObject("files");
        Set<String> filenames = pages.keySet();
        List<Page> result = new ArrayList<>(filenames.size());
        for (String filename : filenames) {
            result.add(new Page(item, title, filename, pages.get(filename).getAsString()));
        }
        System.out.printf(Locale.ROOT, "%s%s%s", USE_UNICODE ? "\uD83D\uDCD6" : "", title, System.lineSeparator());
        return result;
    }
    
    private static List<Page> getPageURLs(HttpClient client, String inventoryUrl, Audit audit) {
        Optional<JsonObject> cache = audit.apply(inventoryUrl);
        if (cache.isPresent()) {
            return toPages(cache.get());
        }
        
        final URI uri;
        try {
            uri = new URL(Env.getServiceURL() + "?url=" + URLEncoder.encode(inventoryUrl, StandardCharsets.UTF_8)).toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new AssertionError(e);
        }
        
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "dev";
        }
        
        final HttpRequest request = HttpRequest.newBuilder(uri).header("Accept", "application/json").header("Accept-Encoding", "gzip").header("User-Agent", "gdd-batch/" + version).timeout(Duration.ofSeconds(20)).build();
        while (true) {
            try {
                HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    System.out.printf(Locale.ROOT, "%s(%d)%s%s", USE_UNICODE ? "\uD83D\uDED1" : "X", response.statusCode(), inventoryUrl, System.lineSeparator());
                    return Collections.emptyList();
                }
                
                JsonObject wrapper = getContent(response);
                audit.accept(inventoryUrl, wrapper);
                return toPages(wrapper);
            } catch (IOException e) {
                // perhaps log?
            } catch (InterruptedException e) {
                throw new Error(e);
            }
        }
    }
    
    private static String sanitizeDirectoryName(CharSequence name) {
        StringBuilder result = new StringBuilder(name.length()).append(name);
        for (int i = 0; i < result.length(); ++i) {
            char c = result.charAt(i);
            switch (c) {
                case '<', '>', ':', '"', '/', '\\', '|', '?', '*' -> result.setCharAt(i, '_');
                case '\t', '\r', '\n' -> result.setCharAt(i, ' ');
                default -> {}
            }
        }
        return result.toString();
    }
    
    private static Path safeResolve(Path root, CharSequence name) {
        StringBuilder mutableName = new StringBuilder(name.length()).append(name);
        while (true) {
            try {
                return root.resolve(mutableName.toString());
            } catch (InvalidPathException e) {
                mutableName.setCharAt(e.getIndex(), '_');
            }
        }
    }
    
    private static void attemptDownload(HttpClient client, HttpRequest request, Path file, int currentAttempt, TaskManager tasks) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        
        String filename = file.getFileName().toString();
        filename = filename.substring(0, filename.lastIndexOf('.'));
        
        final boolean lastAttempt = currentAttempt == 1 + Env.getDownloadRetryCount();
        if (currentAttempt == 0) {
            System.out.printf(Locale.ROOT, "%s%s%s", USE_UNICODE ? "▶️" : "0% ", filename, System.lineSeparator());
        } else {
            System.out.printf(Locale.ROOT, "%s(%d)%s%s", USE_UNICODE ? "\uD83D\uDD01" : "0% ", currentAttempt, filename, System.lineSeparator());
        }
        
        HttpResponse<Path> response;
        long downloadDuration;
        final AtomicBoolean normalTimeout = new AtomicBoolean(false);
        try {
            /* JDK-8208693 -> */
            Thread thread = Thread.currentThread();
            Optional<Duration> timeout = request.timeout();
            ScheduledFuture<?> altTimeoutTask = null;
            if (timeout.isPresent()) {
                long timeoutSeconds = timeout.get().get(ChronoUnit.SECONDS);
                altTimeoutTask = TERMINATOR.schedule(() -> {
                    synchronized (thread) {
                        normalTimeout.set(true);
                        thread.interrupt();
                    }
                }, timeoutSeconds + 3, TimeUnit.SECONDS);
            }
            /* -> JDK-8208693 */
            try {
                long start = System.nanoTime();
                response = client.send(request, BodyHandlers.ofFile(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                downloadDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            } catch (IOException e) {
                if (lastAttempt) {
                    StringWriter writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    System.out.printf(Locale.ROOT, "%s%s %s%s", USE_UNICODE ? "❌" : "X ", filename, writer, System.lineSeparator());
                } else {
                    tasks.schedule(() -> attemptDownload(client, request, file, currentAttempt + 1, tasks), Env.getDownloadRetryDelaySeconds());
                }
                return;
            } finally {
                /* JDK-8208693 -> */
                if (altTimeoutTask != null) {
                    altTimeoutTask.cancel(false);
                    synchronized (thread) {
                        Thread.interrupted();
                    }
                }
                /* -> JDK-8208693 */
            }
            final int statusCode = response.statusCode();
            if (statusCode != 200) {
                if (lastAttempt) {
                    System.out.printf(Locale.ROOT, "%s%s %s%s", USE_UNICODE ? "❌" : "X ", filename, statusCode, System.lineSeparator());
                } else {
                    tasks.schedule(() -> attemptDownload(client, request, file, currentAttempt + 1, tasks), Env.getDownloadRetryDelaySeconds());
                }
                return;
            }
        } catch (InterruptedException e) {
            if (normalTimeout.get()) {
                if (lastAttempt) {
                    System.out.printf(Locale.ROOT, "%s%s%s", USE_UNICODE ? "❌" : "X ", filename, System.lineSeparator());
                } else {
                    tasks.schedule(() -> attemptDownload(client, request, file, currentAttempt + 1, tasks), Env.getDownloadRetryDelaySeconds());
                }
            }
            return;
        }
        
        try {
            Files.move(file, file.resolveSibling(filename));
        } catch (IOException e) {
            // ???
        }
        
        System.out.printf(Locale.ROOT, "%s %.2fs %s%s", USE_UNICODE ? "✔️" : "100%", downloadDuration / 1000D, filename, System.lineSeparator());
    }
    
    public static void main(String[] args) {
        Path downloads = Paths.get(Env.getDownloadPath());
        Audit audit = new Audit(downloads);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMinutes(1)).followRedirects(Redirect.NORMAL).build();
        TaskManager tasks = new TaskManager();
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "dev[" + Env.getServiceURL() + "]";
        }
        
        String realPath;
        try {
            realPath = downloads.toRealPath().toString();
        } catch (IOException e) {
            realPath = downloads.toString();
        }
        System.out.printf(Locale.ROOT, "%s%s %s%d %s%d %s%s%s", USE_UNICODE ? "ℹ️" : "v", version, USE_UNICODE ? "#️⃣" : "#", Env.getWorkerCount(), USE_UNICODE ? "\uD83D\uDD03" : "*", Env.getDownloadRetryCount(), USE_UNICODE ? "↘️" : "", realPath, System.lineSeparator());
        
        Set<String> inventoryURLs = new LinkedHashSet<>();
        for (String arg : args) {
            if (arg.startsWith("https://")) {
                inventoryURLs.add(arg);
            } else {
                inventoryURLs.addAll(readFile(arg));
            }
        }
        
        BufferedReader interactiveInput = null;
        if (inventoryURLs.isEmpty()) {
            interactiveInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            System.out.printf(Locale.ROOT, "%s%d%s", USE_UNICODE ? "\uD83D\uDCDA" : "URLs ", inventoryURLs.size(), System.lineSeparator());
        }
        
        List<Page> pages = new ArrayList<>();
        while (true) {
            if (interactiveInput != null) {
                System.out.printf(Locale.ROOT, "%s: ", USE_UNICODE ? "❓" : "?");
                try {
                    String url = interactiveInput.readLine();
                    if (url == null) {
                        System.out.println();
                        break;
                    }
                    if (url.isBlank()) {
                        continue;
                    }
                    inventoryURLs = Collections.singleton(url);
                } catch (IOException e) {
                    System.out.println();
                    break;
                }
            }
            
            for (String url : inventoryURLs) {
                List<Page> somePages = getPageURLs(client, url.trim(), audit);
                somePages.sort(Comparator.comparing(l -> l.filename));
                pages.addAll(somePages);
            }
            System.out.printf(Locale.ROOT, "%s%d%s", USE_UNICODE ? "\uD83D\uDCC4" : "Files ", pages.size(), System.lineSeparator());
            for (Page page : pages) {
                Path file = safeResolve(safeResolve(downloads, sanitizeDirectoryName(page.item + "_" + page.title)), page.filename);
                if (Files.exists(file)) {
                    continue;
                }
                
                HttpRequest request;
                Path filepart = file.resolveSibling(file.getFileName() + ".filepart");
                try {
                    request = HttpRequest.newBuilder(new URI(page.url)).timeout(Duration.ofMinutes(1)).build();
                } catch (URISyntaxException e) {
                    System.out.printf(Locale.ROOT, "%s%s%s", USE_UNICODE ? "\uD83D\uDED1" : "Invalid: ", page.url, System.lineSeparator());
                    continue;
                }
                tasks.submit(() -> attemptDownload(client, request, filepart, 0, tasks));
            }
            if (interactiveInput == null) {
                break;
            }
            pages.clear();
            while (true) {
                try {
                    tasks.awaitAllTasks();
                    break;
                } catch (ExecutionException e) {
                    // nothing to do here
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.printf(Locale.ROOT, "%s%s", USE_UNICODE ? "\uD83D\uDCAF" : "Done", System.lineSeparator());
        }
        try {
            tasks.shutdown();
        } catch (InterruptedException e) {
            // terminate immediately
        }
        System.out.printf(Locale.ROOT, "%s%s", USE_UNICODE ? "\uD83D\uDCAF" : "Done", System.lineSeparator());
    }
}
