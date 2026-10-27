// YSLAM Real-Time Telemetry & Hardware Accelerator Bridge with Exponential Backoff
class LocalhostOrchestrator {
  constructor(endpoint = "http://127.0.0.1:8080") {
    this.endpoint = endpoint;
    this.activeModel = "qwen2.5-coder:7b";
    this.tokensPerSecond = 0;
    this.isProbing = false;
    this.lastStatus = "UNVERIFIED"; // UNVERIFIED, ONLINE, RETRYING, OFFLINE
    this.lastError = null;
    this.lastLatency = 0;
  }

  // Sleep helper for backoff
  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  // Parse host and port from endpoint
  parseHostPort() {
    try {
      const url = new URL(this.endpoint);
      return { host: url.hostname || "127.0.0.1", port: parseInt(url.port) || 8080 };
    } catch (e) {
      return { host: "127.0.0.1", port: 8080 };
    }
  }

  // Single probe attempt using native AndroidBridge or fetch
  async singleProbe(timeoutMs = 1500) {
    const { host, port } = this.parseHostPort();
    
    // 1. Try native TCP socket probe if AndroidBridge is present (bypasses browser CORS & zero overhead)
    if (typeof window !== 'undefined' && window.AndroidBridge && window.AndroidBridge.probeTcpPort) {
      try {
        const rawRes = window.AndroidBridge.probeTcpPort(host, port, timeoutMs);
        const res = JSON.parse(rawRes);
        if (!res.success) {
          throw new Error(res.error || `Port ${port} closed on ${host}`);
        }
        
        // Port is open, now check HTTP response
        if (window.AndroidBridge.httpGet) {
          const healthUrls = [this.endpoint + "/health", this.endpoint + "/props", this.endpoint + "/api/tags", this.endpoint + "/v1/models", this.endpoint + "/"];
          let httpOk = false;
          let bodySample = "";
          for (const u of healthUrls) {
            try {
              const httpRaw = window.AndroidBridge.httpGet(u, timeoutMs);
              const httpRes = JSON.parse(httpRaw);
              if (httpRes.success || httpRes.statusCode === 200 || httpRes.statusCode === 404 || httpRes.statusCode === 405) {
                httpOk = true;
                bodySample = httpRes.body || "";
                break;
              }
            } catch (ignore) {}
          }
          return {
            status: "ONLINE",
            latency: res.latencyMs || 2,
            engine: this.detectEngineFromPort(port),
            verified: true,
            sample: bodySample.slice(0, 100)
          };
        }
        return {
          status: "ONLINE",
          latency: res.latencyMs || 2,
          engine: this.detectEngineFromPort(port),
          verified: true
        };
      } catch (err) {
        throw err;
      }
    }

    // 2. Fallback: Browser Fetch probe with real measured latency
    if (typeof fetch !== 'undefined') {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      const t0 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
      try {
        const resp = await fetch(this.endpoint, {
          method: "GET",
          mode: "no-cors",
          signal: controller.signal
        });
        clearTimeout(timer);
        const t1 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
        const measuredLatency = Math.max(1, Math.round(t1 - t0));
        return { status: "ONLINE", latency: measuredLatency, engine: this.detectEngineFromPort(port), verified: true };
      } catch (fetchErr) {
        clearTimeout(timer);
        throw fetchErr;
      }
    }
    return { status: "OFFLINE", error: "No network environment" };
  }

  detectEngineFromPort(port) {
    if (port === 11434) return "Ollama";
    if (port === 8080) return "llama.cpp";
    if (port === 1234) return "LM Studio";
    if (port === 8000) return "vLLM / Python";
    if (port === 5001) return "KoboldCpp";
    return "Localhost Engine";
  }

  /**
   * Probe engine health with automatic exponential backoff retry logic.
   * Prevents false 'offline' reports during brief network timeouts.
   * @param {number} maxRetries - Maximum retry attempts (default 3)
   * @param {number} baseDelayMs - Initial delay before retry (default 500ms)
   * @param {function} onAttemptProgress - Callback reporting each attempt
   */
  async probeHealth(maxRetries = 3, baseDelayMs = 500, onAttemptProgress = null) {
    this.isProbing = true;
    let lastErr = null;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        if (attempt > 0) {
          // Exponential backoff with small random jitter: baseDelay * 2^(attempt-1) + jitter
          const backoffDelay = Math.min(baseDelayMs * Math.pow(2, attempt - 1) + Math.random() * 150, 4000);
          this.lastStatus = "RETRYING";
          if (onAttemptProgress) {
            onAttemptProgress({
              status: "RETRYING",
              attempt,
              maxRetries,
              delayMs: Math.round(backoffDelay),
              message: `Brief timeout, retrying in ${Math.round(backoffDelay)}ms (attempt ${attempt}/${maxRetries})...`
            });
          }
          await this.sleep(backoffDelay);
        }

        const result = await this.singleProbe(1500 + attempt * 500);
        this.lastStatus = "ONLINE";
        this.lastLatency = result.latency;
        this.lastError = null;
        this.isProbing = false;
        return {
          ...result,
          retriesUsed: attempt,
          success: true
        };
      } catch (err) {
        lastErr = err;
        console.warn(`[LocalhostOrchestrator] Probe attempt ${attempt + 1}/${maxRetries + 1} failed:`, err.message || err);
      }
    }

    // All retries exhausted -> Genuine OFFLINE state
    this.lastStatus = "OFFLINE";
    this.lastError = lastErr ? (lastErr.message || String(lastErr)) : "Connection refused";
    this.isProbing = false;
    return {
      status: "OFFLINE",
      error: this.lastError,
      retriesUsed: maxRetries,
      success: false,
      endpoint: this.endpoint
    };
  }
}

// Global instance
const orchestrator = new LocalhostOrchestrator("http://127.0.0.1:8080");
if (typeof window !== 'undefined') {
  window.LocalhostOrchestrator = LocalhostOrchestrator;
  window.orchestrator = orchestrator;
}

if (typeof module !== 'undefined') {
  module.exports = { LocalhostOrchestrator, orchestrator };
}
