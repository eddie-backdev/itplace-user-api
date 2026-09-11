package com.itplace.userapi.map.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MapResponseCompressionFilterTest {
    private final MapResponseCompressionFilter filter = new MapResponseCompressionFilter();

    @Test
    void streamsCompressedPublicMapJsonAndPreservesVary() throws Exception {
        var response = call("/api/v1/maps/stores/in-view/previews/compact", "br, gzip;q=1", 200);
        assertThat(response.getHeader("Content-Encoding")).isEqualTo("gzip");
        assertThat(response.getHeaders("Vary")).contains("Origin", "Accept-Encoding");
        assertThat(response.getHeader("Content-Length")).isNull();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"stores\":[\"서울\"]}");
        }
    }

    @Test
    void forwardsLargeCompressedChunksWithoutWritingEachByteToServletStream() throws Exception {
        AtomicInteger singleWrites = new AtomicInteger();
        AtomicInteger bulkWrites = new AtomicInteger();
        var response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                ServletOutputStream delegate = super.getOutputStream();
                return new ServletOutputStream() {
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                    @Override public void write(int value) throws IOException {
                        singleWrites.incrementAndGet();
                        delegate.write(value);
                    }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        bulkWrites.incrementAndGet();
                        delegate.write(bytes, offset, length);
                    }
                };
            }
        };
        byte[] randomBytes = new byte[32_768];
        new Random(20260911).nextBytes(randomBytes);
        String json = "{\"random\":\"" + Base64.getEncoder().encodeToString(randomBytes) + "\"}";
        var request = new MockHttpServletRequest("GET", "/api/v1/maps/stores/in-view/previews/compact");
        request.addHeader("Accept-Encoding", "gzip");

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        });

        assertThat(response.getContentAsByteArray()).hasSizeGreaterThan(8192);
        assertThat(singleWrites.get()).isZero();
        assertThat(bulkWrites.get()).isGreaterThan(1);
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(json);
        }
    }

    @Test
    void respectsEncodingOptOutAndLeavesPrivateOrErrorResponsesUncompressed() throws Exception {
        for (String encoding : new String[]{"gzip;q=0", "br", "gzip;q=invalid"}) {
            assertThat(call("/api/v1/maps/stores/in-view/clusters", encoding, 200)
                    .getHeader("Content-Encoding")).isNull();
        }
        assertThat(call("/api/v1/auth/csrf", "gzip", 200).getHeader("Content-Encoding")).isNull();
        assertThat(call("/api/v1/maps/nearby/itplace-ai", "gzip", 200).getHeader("Content-Encoding")).isNull();
        assertThat(call("/api/v1/maps/stores/in-view/clusters", "gzip", 500).getHeader("Content-Encoding")).isNull();
    }

    @Test
    void resetBufferStartsANewGzipMemberAndSendErrorDiscardsCompression() throws Exception {
        for (boolean error : new boolean[]{false, true}) {
            var request = new MockHttpServletRequest("GET", "/api/v1/maps/stores/in-view/clusters");
            request.addHeader("Accept-Encoding", "gzip");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                var http = (jakarta.servlet.http.HttpServletResponse) res;
                http.setContentType("application/json");
                var stream = http.getOutputStream();
                stream.write("discarded".getBytes(StandardCharsets.UTF_8));
                if (error) http.sendError(500);
                else {
                    http.resetBuffer();
                    http.setIntHeader("Content-Length", 2);
                    stream.write("[]".getBytes(StandardCharsets.UTF_8));
                }
            });
            if (error) {
                assertThat(response.getStatus()).isEqualTo(500);
                assertThat(response.getHeader("Content-Encoding")).isNull();
            } else {
                assertThat(response.getHeader("Content-Length")).isNull();
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                    assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("[]");
                }
            }
        }
    }

    private MockHttpServletResponse call(String path, String encoding, int status) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Accept-Encoding", encoding);
        var response = new MockHttpServletResponse();
        response.addHeader("Vary", "Origin");
        filter.doFilter(request, response, (req, res) -> {
            var http = (jakarta.servlet.http.HttpServletResponse) res;
            http.setStatus(status);
            http.setContentType("application/json");
            byte[] body = "{\"stores\":[\"서울\"]}".getBytes(StandardCharsets.UTF_8);
            http.setContentLength(body.length);
            http.getOutputStream().write(body);
        });
        return response;
    }
}
