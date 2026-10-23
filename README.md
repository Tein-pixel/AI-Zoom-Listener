# AI-Zoom Listener

A minimal Android app that records short microphone segments, transcribes speech through Gemini, and generates contextual response suggestions through NVIDIA NIM (or optional OpenRouter).

## What works in this repository

- Android app source compatible with Android 10 (API 29) and newer.
- Microphone permission is requested visibly before recording begins.
- Recording is split into 12-second audio segments and processed in order.
- Transcript and AI suggestions appear in the app.
- API credentials stay on the backend; they are not embedded in the APK.
- GitHub Actions checks backend JavaScript syntax, builds the APK, uploads an artifact, and publishes a GitHub Release.

## Important audio limitation

The app records through the phone's microphone. It does not guarantee that it can capture Zoom's internal audio. Android restricts playback capture, and audio from calls, headset routes, or apps that disallow capture may not be available. For a useful transcript, the microphone must hear the speaker clearly. Use recording only when you have the required permission and consent.

## 1. Deploy the backend on Vercel

1. Sign in to Vercel and import this repository: https://github.com/Tein-pixel/AI-Zoom-Listener
2. Keep the project root at the repository root. Vercel will discover the functions in the api folder.
3. Add the following Environment Variables in the Vercel project settings, for Production (and Preview too if needed):
   - GEMINI_API_KEY — required for audio transcription.
   - NVIDIA_API_KEY — recommended for response suggestions from NVIDIA NIM.
   - NVIDIA_MODEL — optional; default is openai/gpt-oss-20b.
   - OPENROUTER_API_KEY — optional alternative if no NVIDIA key is set.
   - OPENROUTER_MODEL — optional; default is openrouter/auto.
4. Redeploy after adding environment variables.
5. Copy the deployed HTTPS URL, such as https://your-project.vercel.app.

Create API keys in the providers' own dashboards. Do not put keys in source files, the Android app, screenshots, or Git commits.

## 2. Install the APK

After the first successful GitHub Actions run:
1. Open the repository's Actions tab and select the latest successful build to download the workflow artifact; or open Releases and download app-debug.apk.
2. Install it on the Android phone.
3. Enter the Vercel HTTPS deployment URL and an optional meeting topic, then tap Simpan konfigurasi.
4. Tap Mulai dengarkan and grant microphone permission. Tap Berhenti to stop.

## Build setup

- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- compileSdk 35, minSdk 29, targetSdk 35
- Application ID: com.teinzz.aizoomlistener

## API endpoints

- POST /api/transcribe with audioBase64 and mimeType; returns transcript.
- POST /api/answer with transcript, context, and optional topic; returns answer.

This is an unsigned debug APK for testing, not a Play Store release.


## Automated checks

The GitHub Actions workflow validates Android package configuration, foreground microphone service wiring, Vercel routes, and backend provider configuration before compiling the APK.

## Current deployment

- Backend URL: `https://ai-zoom-listener.vercel.app`
- Health endpoint: `GET /health` (reports whether API keys are configured).
- The deployment can build without provider keys, but AI requests return a clear configuration error until keys are added in Vercel Project Settings → Environment Variables.
- Set `GEMINI_API_KEY` for transcription and either `NVIDIA_API_KEY` or `OPENROUTER_API_KEY` for response suggestions, then redeploy.
