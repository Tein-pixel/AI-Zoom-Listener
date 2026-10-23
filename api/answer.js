function send(res, status, payload) {
  res.status(status).json(payload);
}

export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "POST") return send(res, 405, { error: "Gunakan metode POST." });

  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch { return send(res, 400, { error: "JSON tidak valid." }); }
  }
  if (!body || typeof body !== "object") return send(res, 400, { error: "Body permintaan tidak valid." });

  const transcript = typeof body.transcript === "string" ? body.transcript.trim().slice(0, 5000) : "";
  const context = typeof body.context === "string" ? body.context.trim().slice(-7000) : "";
  const topic = typeof body.topic === "string" ? body.topic.trim().slice(0, 240) : "";
  if (transcript.length < 2) return send(res, 400, { error: "Transkrip terlalu pendek untuk dibuatkan saran." });

  const nvidiaKey = process.env.NVIDIA_API_KEY;
  const openRouterKey = process.env.OPENROUTER_API_KEY;
  const useOpenRouter = !nvidiaKey && Boolean(openRouterKey);
  if (!nvidiaKey && !openRouterKey) {
    return send(res, 500, { error: "Atur NVIDIA_API_KEY atau OPENROUTER_API_KEY di Vercel." });
  }

  const endpoint = useOpenRouter
    ? "https://openrouter.ai/api/v1/chat/completions"
    : "https://integrate.api.nvidia.com/v1/chat/completions";
  const model = useOpenRouter
    ? (process.env.OPENROUTER_MODEL || "openrouter/auto")
    : (process.env.NVIDIA_MODEL || "openai/gpt-oss-20b");
  const system = [
    "Kamu adalah asisten pendamping rapat dan belajar berbahasa Indonesia.",
    "Gunakan transkrip sebagai konteks yang mungkin tidak sempurna; jangan mengarang fakta atau mengaku mendengar bagian yang tidak tersedia.",
    "Berikan saran jawaban singkat dan relevan berdasarkan isi pembicaraan terakhir.",
    "Jika terdengar seperti pertanyaan, usulkan contoh jawaban yang bisa dipahami pengguna, lalu beri penjelasan singkat bila berguna.",
    "Jika konteks belum cukup, nyatakan informasi apa yang belum jelas dan ajukan satu pertanyaan klarifikasi yang tepat.",
    "Jangan mengklaim bahwa saran pasti benar. Jawab langsung tanpa pembukaan panjang."
  ].join(" ");

  const userContent = "Topik: " + (topic || "Tidak ditentukan") +
    "\nKonteks transkrip sebelumnya:\n" + (context || "(belum ada)") +
    "\n\nPotongan transkrip terbaru:\n" + transcript +
    "\n\nBuat saran respons dalam bahasa Indonesia.";

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + (useOpenRouter ? openRouterKey : nvidiaKey),
        "Content-Type": "application/json",
        "Accept": "application/json"
      },
      body: JSON.stringify({
        model,
        messages: [
          { role: "system", content: system },
          { role: "user", content: userContent }
        ],
        temperature: 0.35,
        max_tokens: 500,
        stream: false
      })
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message = data?.error?.message || data?.message || "Permintaan ke model AI gagal.";
      return send(res, response.status >= 400 && response.status < 500 ? response.status : 502, { error: String(message).slice(0, 350) });
    }
    const answer = data?.choices?.[0]?.message?.content;
    const text = typeof answer === "string" ? answer.trim() : "";
    if (!text) return send(res, 502, { error: "Model AI tidak mengembalikan teks jawaban." });
    return send(res, 200, { answer: text, provider: useOpenRouter ? "OpenRouter" : "NVIDIA NIM", model });
  } catch (error) {
    return send(res, 502, { error: "Koneksi ke penyedia model gagal: " + String(error?.message || error).slice(0, 220) });
  }
}
