function send(res, status, payload) {
  const body = JSON.stringify(payload);
  const headers = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store, max-age=0" };

  // Support both Vercel's Node response helpers and a plain Node ServerResponse.
  if (res && typeof res.status === "function" && typeof res.json === "function") {
    res.setHeader("Cache-Control", headers["Cache-Control"]);
    return res.status(status).json(payload);
  }
  if (res && typeof res.end === "function") {
    res.statusCode = status;
    if (typeof res.setHeader === "function") {
      for (const [key, value] of Object.entries(headers)) res.setHeader(key, value);
    }
    res.end(body);
    return;
  }

  // Defensive fallback for runtimes using the Web Request/Response contract.
  return new Response(body, { status, headers });
}

export default async function handler(req, res) {
  try {
    const url = req && typeof req.url === "string" ? req.url : "/";
    const pathname = new URL(url, "https://internal.invalid").pathname;
    const method = req && typeof req.method === "string" ? req.method.toUpperCase() : "GET";

    if ((pathname === "/" || pathname === "/health") && (method === "GET" || method === "HEAD")) {
      return send(res, 200, {
        name: "AI-Zoom Listener API",
        status: "ok",
        endpoints: {
          health: "GET /api/health",
          transcribe: "POST /api/transcribe",
          answer: "POST /api/answer"
        },
        configured: {
          gemini: Boolean(process.env.GEMINI_API_KEY),
          responseModel: Boolean(process.env.NVIDIA_API_KEY || process.env.OPENROUTER_API_KEY)
        }
      });
    }

    return send(res, 404, { error: "Endpoint tidak ditemukan." });
  } catch (error) {
    // Keep root health checks informative without leaking secrets or stack traces.
    return send(res, 500, {
      error: "Gagal menjalankan endpoint status.",
      detail: String(error && error.message ? error.message : "Unknown error").slice(0, 180)
    });
  }
}
