package haven;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitHubVersionFetcher {
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    // The callback runs on the fetching thread once the answer is in, so
    // that a slow or unreachable GitHub does not hold up whoever asked.
    public static void fetchLatestVersion(String owner, String repo, VersionCallback callback) {
        // Set loading state
        callback.onVersionFetched("Loading...");

        executor.submit(() -> {
            String version;
            try {
                version = getLatestReleaseVersion(owner, repo);
            } catch (Exception e) {
                version = "Failed";
            }
            callback.onVersionFetched(version);
        });
    }

    private static String getLatestReleaseVersion(String owner, String repo) throws Exception {
        String urlString = String.format("https://api.github.com/repos/%s/%s/releases/latest", owner, repo);
        HttpURLConnection connection = null;
        BufferedReader br = null;

        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

            if (connection.getResponseCode() != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + connection.getResponseCode());
            }

            br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String output;

            while ((output = br.readLine()) != null) {
                response.append(output);
            }

            return parseTagName(response.toString()); // Pass the entire response to parse
        } finally {
            if (br != null) {
                br.close(); // Close BufferedReader
            }
            if (connection != null) {
                connection.disconnect(); // Close the connection
            }
        }
    }

    private static String parseTagName(String jsonResponse) {
        String tagNameKey = "\"tag_name\":";
        int startIndex = jsonResponse.indexOf(tagNameKey) + tagNameKey.length();
        int endIndex = jsonResponse.indexOf("\"", startIndex + 1);
        return jsonResponse.substring(startIndex + 1, endIndex); // Extract the version string
    }

    // Define the callback interface as a nested interface
    public interface VersionCallback {
        void onVersionFetched(String version);
    }
}
