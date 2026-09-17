package ru.otus.hw.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class W3CTraceContextAdapter {

    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final Tracer tracer;

    private final Propagator propagator;

    public Map<String, String> captureCurrent() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == TraceContext.NOOP || span.context().traceId().isBlank()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(span.context(), headers,
                (carrier, key, value) -> {
                    assert carrier != null;
                    carrier.put(key, value);
                });
        return Map.copyOf(headers);
    }

    public <C> void injectCurrent(C carrier, Propagator.Setter<C> setter) {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == TraceContext.NOOP || span.context().traceId().isBlank()) {
            return;
        }
        propagator.inject(span.context(), carrier, setter);
    }

    public <C> void inject(C carrier, Propagator.Setter<C> setter, String traceparent, String tracestate) {
        if (traceparent != null && !traceparent.isBlank()) {
            setter.set(carrier, "traceparent", traceparent);
        }
        if (tracestate != null && !tracestate.isBlank()) {
            setter.set(carrier, "tracestate", tracestate);
        }
    }

    public Scope open(Map<String, ?> headers, String spanName) {
        String traceparent = value(headers, "traceparent");
        Span.Builder builder;
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            builder = tracer.spanBuilder().setNoParent();
        } else {
            builder = propagator.extract(headers,
                    W3CTraceContextAdapter::value);
        }
        Span span = builder.name(spanName).kind(Span.Kind.CONSUMER).start();
        return new Scope(tracer.withSpan(span), span);
    }

    private static String value(Map<String, ?> headers, String key) {
        Object value = headers.get(key);
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value == null ? null : value.toString();
    }

    public static final class Scope implements AutoCloseable {

        private final Tracer.SpanInScope scope;

        private final Span span;

        private Scope(Tracer.SpanInScope scope, Span span) {
            this.scope = scope;
            this.span = span;
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
