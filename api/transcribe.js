const MAX_AUDIO_BASE64 = 3_800_000;

function send(res, status, payload) {
  res.status(status).json(payload);
}

export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "POST") return send(res, 405, { error: "Gunakan metode POST." });

  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) return send(res, 500, { error: "GEMINI_API_KEY belum diatur pada Vercel." });

  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch { return send(res, 400, { error: "JSON tidak valid." }); }
  }
  if (!body || typeof body !== "object") return send(res, 400, { error: "Body permintaan tidak valid." });

  const audioBase64 = body.audioBase64;
  const mimeType = typeof body.mimeType === "string" ? body.mimeType : "audio/mp4";
  if (typeof audioBase64 !== "string" || audioBase64.length < 100) {
    return send(res, 400, { error: "Data audio kosong atau terlalu pendek." });
  }
  if (audioBase64.length > MAX_AUDIO_BASE64) return send(res, 413, { error: "Audio terlalu besar. Coba lagi dengan potongan lebih pendek." });
  if (!/^audio\/[a-zA-Z0-9.+-]+$/.test(mimeType)) return send(res, 400, { error: "Tipe audio tidak didukung." });

  try {
    const response = await fetch("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=" + encodeURIComponent(apiKey), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{
          role: "user",
          parts: [
            { text: "Transkripsikan audio ini secara akurat ke dalam bahasa Indonesia. Pertahankan istilah penting, angka, dan nama. Jangan menjawab pertanyaan dalam audio, jangan meringkas, dan jangan menambahkan kata yang tidak terdengar. Jika ucapan bukan bahasa Indonesia, transkripsikan sesuai bahasa yang terdengar. Keluarkan hanya teks transkrip." },
            { inlineData: { mimeType, data: audioBase64 } }
          ]
        }],
        generationConfig: { temperature: 0 }
      })
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message = data?.error?.message || "Permintaan transkripsi ke Gemini gagal.";
      return send(res, response.status >= 400 && response.status < 500 ? response.status : 502, { error: message.slice(0, 350) });
    }
    const transcript = (data?.candidates?.[0]?.content?.parts || [])
      .map(part => typeof part.text === "string" ? part.text : "")
      .join("\n")
      .trim();
    if (!transcript) return send(res, 502, { error: "Gemini tidak mengembalikan transkrip. Coba rekam ulang dengan suara lebih jelas." });
    return send(res, 200, { transcript });
  } catch (error) {
    return send(res, 502, { error: "Koneksi ke Gemini gagal: " + String(error?.message || error).slice(0, 220) });
  }
}
