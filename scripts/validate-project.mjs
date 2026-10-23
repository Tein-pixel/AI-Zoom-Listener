import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const requiredFiles = [
  "settings.gradle",
  "build.gradle",
  "gradle.properties",
  "app/build.gradle",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/com/teinzz/aizoomlistener/MainActivity.java",
  "app/src/main/java/com/teinzz/aizoomlistener/AudioListenerService.java",
  "app/src/main/java/com/teinzz/aizoomlistener/FloatingAssistantService.java",
  "api/transcribe.js",
  "api/answer.js",
  "api/health.js",
  "index.js",
  "vercel.json",
  ".github/workflows/build-apk.yml"
];

for (const file of requiredFiles) {
  assert.ok(existsSync(file), "Missing required project file: " + file);
}

const gradle = readFileSync("app/build.gradle", "utf8");
const manifest = readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const activity = readFileSync("app/src/main/java/com/teinzz/aizoomlistener/MainActivity.java", "utf8");
const service = readFileSync("app/src/main/java/com/teinzz/aizoomlistener/AudioListenerService.java", "utf8");
const floating = readFileSync("app/src/main/java/com/teinzz/aizoomlistener/FloatingAssistantService.java", "utf8");
const transcribe = readFileSync("api/transcribe.js", "utf8");
const answer = readFileSync("api/answer.js", "utf8");
const workflow = readFileSync(".github/workflows/build-apk.yml", "utf8");
const health = readFileSync("api/health.js", "utf8");
const router = readFileSync("index.js", "utf8");
const landing = readFileSync("index.html", "utf8");
const vercel = readFileSync("vercel.json", "utf8");

assert.match(gradle, /namespace\s+['"]com\.teinzz\.aizoomlistener['"]/);
assert.match(gradle, /applicationId\s+['"]com\.teinzz\.aizoomlistener['"]/);
assert.match(gradle, /minSdk\s+29/);
assert.match(manifest, /android\.permission\.RECORD_AUDIO/);
assert.match(manifest, /android\.permission\.FOREGROUND_SERVICE_MICROPHONE/);
assert.match(manifest, /android\.permission\.SYSTEM_ALERT_WINDOW/);
assert.match(manifest, /android\.permission\.FOREGROUND_SERVICE_SPECIAL_USE/);
assert.match(manifest, /foregroundServiceType="microphone"/);
assert.match(manifest, /foregroundServiceType="specialUse"/);
assert.match(manifest, /android:name="\.AudioListenerService"/);
assert.match(manifest, /android:name="\.FloatingAssistantService"/);
assert.match(activity, /FloatingAssistantService\.ACTION_SHOW/);
assert.match(activity, /Settings\.ACTION_MANAGE_OVERLAY_PERMISSION/);
assert.match(activity, /startForegroundService/);
assert.match(floating, /TYPE_APPLICATION_OVERLAY/);
assert.match(floating, /ACTION_EDIT_TRANSCRIPT/);
assert.match(floating, /class OrbView extends View/);
assert.match(service, /FOREGROUND_SERVICE_TYPE_MICROPHONE/);
assert.match(service, /ACTION_EDIT_TRANSCRIPT/);
assert.match(service, /\/api\/transcribe/);
assert.match(service, /\/api\/answer/);
assert.match(health, /AI-Zoom Listener API/);
assert.match(health, /GEMINI_API_KEY/);
assert.match(health, /OPENROUTER_API_KEY/);
assert.match(router, /AI-Zoom Listener API/);
assert.match(router, /res\.end/);
assert.match(router, /new Response/);
assert.match(landing, /AI-Zoom Listener API/);
assert.ok(vercel.includes('"destination": "/index.html"'));
assert.match(vercel, /maxDuration/);
assert.match(transcribe, /GEMINI_API_KEY/);
assert.match(transcribe, /gemini-3\.8-flash/);
assert.match(answer, /NVIDIA_API_KEY/);
assert.match(answer, /OPENROUTER_API_KEY/);
assert.match(workflow, /assembleDebug/);
assert.match(workflow, /pull_request:/);

console.log("Project validation passed: Android package, microphone foreground service, APIs, provider configuration, and CI build are present.");
