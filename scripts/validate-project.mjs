import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const files = [
  "settings.gradle",
  "build.gradle",
  "gradle.properties",
  "app/build.gradle",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/com/teinzz/aizoomlistener/MainActivity.java",
  "api/transcribe.js",
  "api/answer.js",
  ".github/workflows/build-apk.yml"
];

for (const file of files) {
  assert.ok(existsSync(file), "Missing required project file: " + file);
}

const gradle = readFileSync("app/build.gradle", "utf8");
const manifest = readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const activity = readFileSync("app/src/main/java/com/teinzz/aizoomlistener/MainActivity.java", "utf8");
const transcribe = readFileSync("api/transcribe.js", "utf8");
const answer = readFileSync("api/answer.js", "utf8");
const workflow = readFileSync(".github/workflows/build-apk.yml", "utf8");

assert.match(gradle, /namespace\s+['"]com\.teinzz\.aizoomlistener['"]/);
assert.match(gradle, /applicationId\s+['"]com\.teinzz\.aizoomlistener['"]/);
assert.match(gradle, /minSdk\s+29/);
assert.match(manifest, /android\.permission\.RECORD_AUDIO/);
assert.match(activity, /\/api\/transcribe/);
assert.match(activity, /\/api\/answer/);
assert.match(transcribe, /GEMINI_API_KEY/);
assert.match(transcribe, /gemini-3\.8-flash/);
assert.match(answer, /NVIDIA_API_KEY/);
assert.match(answer, /OPENROUTER_API_KEY/);
assert.match(workflow, /assembleDebug/);
assert.match(workflow, /pull_request:/);

console.log("Project validation passed: Android package, permissions, API endpoints, provider config, and CI build are present.");
