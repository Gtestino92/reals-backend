// Run from the repository root with node --test scripts/test_bruno_dev_auth.cjs.
const assert = require("node:assert/strict");
const { readFileSync, readdirSync } = require("node:fs");
const test = require("node:test");
const vm = require("node:vm");

const directory = "bruno/16 - AWS Dev Tooling";
const requests = new Map();
for (const file of readdirSync(directory).filter(name => name.endsWith(".bru"))) {
  const source = readFileSync(directory + "/" + file, "utf8").replaceAll("\r\n", "\n");
  requests.set(file.split("-")[0], source);
}

test("fresh hosted DEV session, protected routes, recovery and revoke", async () => {
  const env = { baseUrl: "https://dev.example", firebase_admin_password: "synthetic", firebase_app_check_debug_token: "synthetic", firebase_api_key: "mock", firebase_project_number: "123",
    firebase_admin_web_app_id: "1:123:web:abc", firebase_admin_email: "admin@example.org", firebase_admin_expected_uid: "admin" };
  const runtime = {};
  const now = Date.now();
  const claims = { sub: "admin", email_verified: true, email: env.firebase_admin_email,
    firebase: { sign_in_provider: "password" }, exp: Math.floor(now / 1000) + 300 };
  const token = JSON.stringify(claims);
  const grant = "g".repeat(43);
  const bru = {
    getAllEnvVars: () => env,
    getVar: key => runtime[key], getEnvVar: key => env[key],
    setVar: (key, value) => { runtime[key] = value; }, deleteVar: key => delete runtime[key],
    interpolate: input => input.replace(/{{(\w+)}}/g, (_, key) => runtime[key] ?? env[key])
  };
  async function script(number, phase, body, status = 200, folder = false) {
    const name = requests.get(number).match(/  name: (.+)/)[1];
    const source = requests.get(folder ? "folder.bru" : number);
    const block = source.match(new RegExp("script:" + phase + " \\{\\n([\\s\\S]*?)\\n\\}"));
    if (!block) return;
    await vm.runInNewContext("(async () => {" + block[1] + "})()", {
      bru, req: { getName: () => name }, res: { getBody: () => body, getStatus: () => status },
      require: () => ({ decode: value => value ? JSON.parse(value) : null }), console
    });
  }
  const guard = number => script(number, "pre-request", null, 200, true);
  const post = (number, body, status) => script(number, "post-response", body, status);
  await assert.rejects(guard("60"));
  await guard("00b");
  await script("00b", "pre-request");
  await assert.rejects(post("00b", {}));
  await post("00b", { token });
  await script("00", "pre-request");
  await assert.rejects(post("00", {}, 400));
  await assert.rejects(post("00", { idToken: JSON.stringify({ ...claims, email_verified: false }) }));
  assert.equal(runtime.devAdminIdToken, "");
  await post("00", { idToken: token });
  await guard("05");
  await post("05", { configured: true, grantValid: false });
  await assert.rejects(guard("60"));
  await assert.rejects(guard("99"), /No local grant/);
  await guard("06");
  await assert.rejects(post("06", { grant, expiresAt: new Date(now).toISOString() }));
  const verified = { grant, expiresAt: new Date(now + 120000).toISOString() };
  await post("06", verified);
  await post("05", { configured: true, grantValid: true });
  for (const [number, source] of requests) {
    assert.doesNotMatch(source, /vars:pre-request|process\.env/);
    if (number === "98" || !(Number(number) >= 10)) continue;
    await guard(number);
    const headers = bru.interpolate(source.match(/headers \{([\s\S]*?)\n\}/)[1]);
    assert.ok(headers.includes("Authorization: Bearer " + token));
    assert.ok(headers.includes("X-Firebase-AppCheck: " + token));
    assert.ok(headers.includes("X-Reals-Admin-Grant: " + grant));
  }
  runtime.baseUrl = "https://shadow.example";
  await assert.rejects(guard("60"));
  delete runtime.baseUrl;
  env.devChatId = "configured-fixture";
  runtime.devChatId = "shadow-fixture";
  await assert.rejects(guard("48"), /override for devChatId/);
  delete runtime.devChatId;
  runtime.devAdminGrantExpiry = new Date(now).toISOString();
  await assert.rejects(guard("60"));
  await guard("99");
  for (const key of ["firebase_admin_email", "firebase_admin_expected_uid", "firebase_api_key", "firebase_project_number", "firebase_admin_web_app_id"]) {
    const original = env[key];
    env[key] += "-different";
    await assert.rejects(guard("99"), /original DEV configuration/);
    env[key] = original;
  }
  runtime.devAdminAppCheck = JSON.stringify({ exp: 0 });
  await assert.rejects(guard("06"));
  await post("00b", { token });
  await script("00", "pre-request");
  await post("00", { idToken: token });
  await post("06", verified);
  await guard("99");
  await assert.rejects(post("99", {}, 403));
  assert.equal(runtime.devAdminGrant, grant);
  await post("99", {}, 204);
  assert.deepEqual(runtime, {});
  runtime.devAdminGrant = grant;
  runtime.devAdminIdToken = JSON.stringify({ exp: 0 });
  await guard("98");
  assert.deepEqual(runtime, {});
  await assert.rejects(guard("60"));
  await guard("00b");
  await post("00b", { token });
  env.firebase_admin_expected_uid = "different";
  await guard("00");
  assert.equal(runtime.devAdminAppCheck, undefined);
});
