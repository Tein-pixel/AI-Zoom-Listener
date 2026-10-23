export default function handler(req, res) {
  res.setHeader("Cache-Control", "no-store, max-age=0");
  res.setHeader("Content-Type", "application/json; charset=utf-8");

  if (req.method !== "GET" && req.method !== "HEAD") {
    res.setHeader("Allow", "GET, HEAD");
    return res.status(405).json({ status: "method_not_allowed" });
  }

  return res.status(200).json({
    name: "AI-Zoom Listener API",
    status: "ok",
    endpoints: {
      transcribe: "POST /api/transcribe",
      answer: "POST /api/answer"
    },
    configured: {
      gemini: Boolean(process.env.GEMINI_API_KEY),
      responseModel: Boolean(process.env.NVIDIA_API_KEY || process.env.OPENROUTER_API_KEY)
    }
  });
}
