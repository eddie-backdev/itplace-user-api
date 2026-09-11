package com.itplace.userapi.map.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.FilterOutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Compress public map data only; authentication and personalized responses are outside this filter. */
@Component
public class MapResponseCompressionFilter extends OncePerRequestFilter {
    private static final Set<String> PATHS = Set.of(
            "/api/v1/maps/stores/in-view/clusters", "/api/v1/maps/stores/in-view/previews",
            "/api/v1/maps/stores/in-view/previews/compact", "/api/v1/maps/nearby",
            "/api/v1/maps/nearby/previews", "/api/v1/maps/nearby/category",
            "/api/v1/maps/nearby/category/previews", "/api/v1/maps/nearby/search",
            "/api/v1/maps/nearby/search/previews", "/api/v1/mobile/map/nearby",
            "/api/v1/maps/nearby/previews/compact", "/api/v1/maps/nearby/category/previews/compact",
            "/api/v1/maps/nearby/search/previews/compact");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.addHeader("Vary", "Accept-Encoding");
        if (!acceptsGzip(request.getHeader("Accept-Encoding"))) {
            chain.doFilter(request, response);
            return;
        }
        CompressedResponse wrapped = new CompressedResponse(response);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            wrapped.finish();
        }
    }

    private boolean acceptsGzip(String header) {
        if (header == null) return false;
        for (String encoding : header.split(",")) {
            String[] parts = encoding.trim().split(";");
            if (!parts[0].trim().equalsIgnoreCase("gzip")) continue;
            return Arrays.stream(parts).skip(1).map(String::trim)
                    .filter(part -> part.startsWith("q="))
                    .allMatch(part -> {
                        try { return Double.parseDouble(part.substring(2)) > 0; }
                        catch (NumberFormatException ignored) { return false; }
                    });
        }
        return false;
    }

    private static final class ResettableGzip extends GZIPOutputStream {
        private ResettableGzip(ServletOutputStream delegate) throws IOException {
            super(new FilterOutputStream(delegate) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException { flush(); }
            }, 8192);
        }
        private void discard() { def.end(); }
    }

    private static final class CompressedResponse extends HttpServletResponseWrapper {
        private ResettableGzip gzip;
        private ServletOutputStream stream;

        private CompressedResponse(HttpServletResponse response) { super(response); }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream != null) return stream;
            ServletOutputStream delegate = super.getOutputStream();
            if (getStatus() != 200 || getHeader("Content-Encoding") != null
                    || getContentType() == null || !getContentType().startsWith("application/json")) {
                stream = delegate;
                return stream;
            }
            setHeader("Content-Encoding", "gzip");
            super.setHeader("Content-Length", null);
            gzip = new ResettableGzip(delegate);
            stream = new ServletOutputStream() {
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                @Override public void write(int value) throws IOException { gzip.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException {
                    gzip.write(value, offset, length);
                }
                @Override public void flush() throws IOException { gzip.flush(); }
            };
            return stream;
        }

        @Override public void setContentLength(int length) {}
        @Override public void setContentLengthLong(long length) {}
        @Override public void setHeader(String name, String value) {
            if (!"Content-Length".equalsIgnoreCase(name)) super.setHeader(name, value);
        }
        @Override public void addHeader(String name, String value) {
            if (!"Content-Length".equalsIgnoreCase(name)) super.addHeader(name, value);
        }

        @Override public void setIntHeader(String name, int value) {
            if (!"Content-Length".equalsIgnoreCase(name)) super.setIntHeader(name, value);
        }
        @Override public void addIntHeader(String name, int value) {
            if (!"Content-Length".equalsIgnoreCase(name)) super.addIntHeader(name, value);
        }
        @Override public void reset() {
            super.reset();
            discard();
        }
        @Override public void resetBuffer() {
            super.resetBuffer();
            if (gzip != null) {
                gzip.discard();
                try { gzip = new ResettableGzip(super.getOutputStream()); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            }
        }
        @Override public void sendError(int status, String message) throws IOException {
            discard();
            super.setHeader("Content-Encoding", null);
            super.sendError(status, message);
        }
        @Override public void sendError(int status) throws IOException { sendError(status, null); }
        private void discard() {
            if (gzip != null) gzip.discard();
            gzip = null;
            stream = null;
        }

        private void finish() throws IOException {
            if (gzip != null) gzip.close();
        }
    }
}
