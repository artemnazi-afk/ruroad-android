package ru.ruroad.karta;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Терпимый TLS для nspd.gov.ru: некоторые провайдеры РФ перехватывают TLS
 * к НСПД (подменный корень телефону не доверен). Данные НСПД публичные,
 * риск подмены некритичен. Используется ТОЛЬКО для nspd.gov.ru.
 */
public final class NspdTls {

    public static final SSLSocketFactory SOCKET_FACTORY;
    public static final HostnameVerifier VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return hostname != null && hostname.endsWith("nspd.gov.ru");
        }
    };

    static {
        SSLSocketFactory f = null;
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            f = ctx.getSocketFactory();
        } catch (Exception e) {
            f = null;
        }
        SOCKET_FACTORY = f;
    }

    private NspdTls() {}
}
