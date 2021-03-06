package com.netflix.dyno.connectionpool.exception;

import java.util.concurrent.TimeUnit;

import com.netflix.dyno.connectionpool.Host;

public class DynoConnectException extends DynoException {

	private static final long serialVersionUID = 5111292446354085002L;

	private Host host = Host.NO_HOST;
    
    private long latency = 0;
    private long latencyWithPool = 0;
    private int attemptCount = 0;

    public DynoConnectException(String message) {
        super(message);
    }

    public DynoConnectException(Throwable t) {
        super(t);
    }

    public DynoConnectException(String message, Throwable cause) {
        super(message, cause);
    }

    public DynoConnectException setHost(Host host) {
        this.host = host;
        return this;
    }

    public Host getHost() {
        return this.host;
    }

    public DynoConnectException setLatency(long latency) {
        this.latency = latency;
        return this;
    }

    public long getLatency() {
        return this.latency;
    }

    public long getLatency(TimeUnit units) {
        return units.convert(this.latency, TimeUnit.NANOSECONDS);
    }

    public DynoException setLatencyWithPool(long latency) {
        this.latencyWithPool = latency;
        return this;
    }

    public long getLatencyWithPool() {
        return this.latencyWithPool;
    }

    @Override
    public String getMessage() {
        return new StringBuilder()
            .append(getClass().getSimpleName())
            .append(": [")
            .append(  "host="    ).append(host.toString())
            .append(", latency=" ).append(latency).append("(").append(latencyWithPool).append(")")
            .append(", attempts=").append(attemptCount)
            .append("]")
            .append(super.getMessage())
            .toString();
    }

    public String getOriginalMessage() {
        return super.getMessage();
    }

    public DynoConnectException setAttempt(int attemptCount) {
        this.attemptCount = attemptCount;
        return this;
    }
}
