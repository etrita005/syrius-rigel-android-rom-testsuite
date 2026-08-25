package com.hmdm.testapp;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real-network verification helpers for the network access control feature
 * (ASR-0135/0136). All traffic of this app is routed through the Launcher's
 * firewall VPN, so these probes exercise the actual policy in effect.
 */
public final class NetworkVerifier {

    private static final String TAG = "NetworkVerifier";

    private static final int DNS_TIMEOUT_SECONDS = 8;
    private static final int TCP_CONNECT_TIMEOUT_MS = 5000;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    private static final int HTTP_READ_TIMEOUT_MS = 3000;

    private NetworkVerifier() {
    }

    /**
     * Resolve a host; a blocked DNS query is answered with REFUSED by the
     * firewall, so the lookup fails fast with UnknownHostException.
     */
    public static Map<String, Object> dnsLookup(String host) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> future = executor.submit(new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    Map<String, Object> result = new LinkedHashMap<>();
                    try {
                        InetAddress[] addresses = InetAddress.getAllByName(host);
                        List<String> ips = new ArrayList<>();
                        for (InetAddress address : addresses) {
                            ips.add(address.getHostAddress());
                        }
                        result.put("resolved", true);
                        result.put("ips", ips);
                    } catch (Exception e) {
                        result.put("resolved", false);
                        result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    return result;
                }
            });
            return future.get(DNS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.i(TAG, "dnsLookup timeout host:" + host);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resolved", false);
            result.put("error", "timeout");
            return result;
        } catch (Exception e) {
            Log.i(TAG, "dnsLookup error host:" + host + " " + e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resolved", false);
            result.put("error", e.getClass().getSimpleName());
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * TCP connect to a host/IP:port. When the destination IP is blocked the
     * SYN is dropped and the connect times out (SocketTimeoutException);
     * a refused/RST error means the packet was delivered but the server
     * rejected the connection.
     */
    public static Map<String, Object> tcpConnect(String host, String port) {
        Map<String, Object> result = new LinkedHashMap<>();
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.trim());
            if (portNumber < 1 || portNumber > 65535) {
                result.put("connected", false);
                result.put("error", "invalid port: " + port);
                return result;
            }
        } catch (NumberFormatException e) {
            result.put("connected", false);
            result.put("error", "invalid port: " + port);
            return result;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final int portFinal = portNumber;
            Future<Map<String, Object>> future = executor.submit(new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    Socket socket = new Socket();
                    try {
                        socket.connect(new InetSocketAddress(host, portFinal), TCP_CONNECT_TIMEOUT_MS);
                        Map<String, Object> ok = new LinkedHashMap<>();
                        ok.put("connected", true);
                        ok.put("remote", socket.getInetAddress() != null
                                ? socket.getInetAddress().getHostAddress() : "");
                        return ok;
                    } catch (Exception e) {
                        Map<String, Object> fail = new LinkedHashMap<>();
                        fail.put("connected", false);
                        fail.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                        return fail;
                    } finally {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            return future.get(TCP_CONNECT_TIMEOUT_MS + 3000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result.put("connected", false);
            result.put("error", "timeout");
            return result;
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName());
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * HTTP GET through the firewall; returns the status code on success.
     */
    public static Map<String, Object> httpGet(String url) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final String urlFinal = url;
            Future<Map<String, Object>> future = executor.submit(new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(urlFinal).openConnection();
                        connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                        connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestMethod("GET");
                        int code = connection.getResponseCode();
                        Map<String, Object> ok = new LinkedHashMap<>();
                        ok.put("status", code);
                        return ok;
                    } catch (Exception e) {
                        Map<String, Object> fail = new LinkedHashMap<>();
                        fail.put("status", -1);
                        fail.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                        return fail;
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
            });
            return future.get(HTTP_CONNECT_TIMEOUT_MS + HTTP_READ_TIMEOUT_MS + 3000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", -1);
            result.put("error", "timeout");
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", -1);
            result.put("error", e.getClass().getSimpleName());
            return result;
        } finally {
            executor.shutdownNow();
        }
    }
}
