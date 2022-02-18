package lt.samerdokas.gdd.batch;

import java.nio.file.Paths;
import java.util.Locale;

class Env {
    public static boolean isTerminalCompatible() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("GDD_UNICODE_TERMINAL", "false").toLowerCase(Locale.ROOT));
    }
    
    public static String getServiceURL() {
        return System.getenv().getOrDefault("GDD_SERVICE_URL", "https://gdd.samerdokas.lt/api/GetDownloadInfo");
    }
    
    public static String getDownloadPath() {
        return System.getenv().getOrDefault("GDD_DOWNLOAD_PATH", Paths.get(System.getProperty("user.home", "."),"Downloads", "GDD").toString());
    }
    
    public static int getWorkerCount() {
        return getEnvInteger("GDD_WORKERS", 4);
    }
    
    public static int getDownloadRetryCount() {
        return getEnvInteger("GDD_DOWNLOAD_RETRIES", 10);
    }
    
    public static int getDownloadRetryDelaySeconds() {
        return getEnvInteger("GDD_DOWNLOAD_RETRY_DELAY", 10);
    }
    
    private static int getEnvInteger(String name, int defaultValue) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
