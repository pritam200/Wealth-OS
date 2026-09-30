package com.marketai.market.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** NSE's JSON shapes (captured from the live endpoints) parsed into dated, newest-first items. */
class NseClientTest {

    HttpServer server;
    NseClient nse;

    static final Map<String, String> BODIES = Map.of(
        "/corporate-announcements", """
            [{"an_dt":"12-Aug-2026 10:01:00","desc":"Analysts/Institutional Investor Meet","attchmntText":"Schedule of meet","attchmntFile":"https://nsearchives.nseindia.com/a.pdf"},
             {"an_dt":"25-Sep-2026 22:49:03","desc":"Outcome of Board Meeting","attchmntText":"Results approved","attchmntFile":"https://nsearchives.nseindia.com/b.pdf"}]""",
        "/corporates-corporateActions", """
            [{"subject":"Dividend - Rs 10 Per Share","exDate":"05-Jun-2026","recDate":"05-Jun-2026"}]""",
        "/event-calendar", """
            [{"purpose":"Financial Results","bm_desc":"To consider Q2 results","date":"05-Aug-2005"},
             {"purpose":"Financial Results","bm_desc":"To consider Q2 results","date":"16-Oct-2026"}]""",
        "/corporates-financial-results", """
            {"data":[{"fromDate":"01-Apr-2026","toDate":"30-Jun-2026","filingDate":"16-Jul-2026 20:20","consolidated":"Consolidated","audited":"Un-Audited","financialYear":"2026-2027"}]}""");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = BODIES.getOrDefault(path, null);
            int code = body == null ? 403 : 200;
            byte[] out = (body == null ? "denied" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        nse = new NseClient(new ObjectMapper(), HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void announcementsNewestFirst() {
        List<NseClient.Item> a = nse.announcements("TEST", 5);
        assertThat(a).extracting(NseClient.Item::title).containsExactly("Outcome of Board Meeting", "Analysts/Institutional Investor Meet");
        assertThat(a.get(0).date()).isEqualTo(LocalDateTime.of(2026, 9, 25, 22, 49, 3));
        assertThat(a.get(0).url()).isEqualTo("https://nsearchives.nseindia.com/b.pdf");
        assertThat(a.get(0).kind()).isEqualTo("FILING");
    }

    @Test
    void actionsMeetingsAndResults() {
        assertThat(nse.corporateActions("TEST", 5).get(0).date()).isEqualTo(LocalDateTime.of(2026, 6, 5, 0, 0));
        // the calendar comes oldest first; the upcoming meeting must be first
        assertThat(nse.boardMeetings("TEST", 1).get(0).date()).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0));
        NseClient.Item r = nse.resultsFilings("TEST", 2).get(0);
        assertThat(r.title()).isEqualTo("Quarterly results filed for 01-Apr-2026 to 30-Jun-2026");
        assertThat(r.date()).isEqualTo(LocalDateTime.of(2026, 7, 16, 20, 20));
    }

    @Test
    void failureIsAnErrorNotAnEmptyList() {
        server.removeContext("/");
        server.createContext("/", ex -> { ex.sendResponseHeaders(403, -1); ex.close(); });
        assertThatThrownBy(() -> nse.announcements("TEST", 5)).hasMessage("NSE HTTP 403");
    }
}
