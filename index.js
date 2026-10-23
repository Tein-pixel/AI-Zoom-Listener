import transcribe from "./api/transcribe.js";
import answer from "./api/answer.js";

export default async function handler(req, res) {
  const pathname = new URL(req.url || "/", "https://internal.invalid").pathname;
  if (pathname === "/api/transcribe" || pathname === "/api/transcribe/") {
    return transcribe(req, res);
  }
  if (pathname === "/api/answer" || pathname === "/api/answer/") {
    return answer(req, res);
  }
  if (req.method === "GET" && (pathname === "/" || pathname === "/health")) {
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    return res.status(200).json({
      name: "AI-Zoom Listener API",
      status: "ok",
      endpoints: ["POST /api/transcribe", "POST /api/answer"],
      configured: {
        gemini: Boolean(process.env.GEMINI_API_KEY),
        responseModel: Boolean(process.env.NVIDIA_API_KEY || process.env.OPENROUTER_API_KEY)
      }
    });
  }
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  return res.status(404).json({ error: "Endpoint tidak ditemukan." });
}
