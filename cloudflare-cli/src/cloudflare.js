import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const CONFIG_DIR = process.env.XDG_CONFIG_HOME
  ? path.join(process.env.XDG_CONFIG_HOME, "cloudflare-cli")
  : path.join(os.homedir(), ".config", "cloudflare-cli");

export const CONFIG_FILE_PATH = path.join(CONFIG_DIR, "config.json");

export async function loadConfig() {
  try {
    const raw = await readFile(CONFIG_FILE_PATH, "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed.apiToken || !parsed.zoneId || !parsed.domain) {
      return null;
    }
    return parsed;
  } catch (error) {
    if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

export async function saveConfig(config) {
  await mkdir(CONFIG_DIR, { recursive: true });
  await writeFile(CONFIG_FILE_PATH, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  await chmod(CONFIG_FILE_PATH, 0o600).catch(() => {});
}

export async function validateCredentials(config) {
  const response = await fetch(`https://api.cloudflare.com/client/v4/zones/${config.zoneId}`, {
    headers: {
      Authorization: `Bearer ${config.apiToken}`,
      "Content-Type": "application/json",
    },
  });

  const body = await response.json();
  if (!response.ok || body.success !== true) {
    throw new Error("Cloudflare login failed. Check your API token and zone id.");
  }
}

export async function purgeCache(config, requestBody) {
  const response = await fetch(`https://api.cloudflare.com/client/v4/zones/${config.zoneId}/purge_cache`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${config.apiToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(requestBody),
  });

  const body = await response.json();
  if (!response.ok || body.success !== true) {
    throw new Error(`Cloudflare purge failed: ${JSON.stringify(body)}`);
  }
}

export function expandPurgeTargets(targets, domain) {
  return targets.map((target) => normalizeTarget(target, domain));
}

export function normalizeTarget(target, domain) {
  if (target.startsWith("https://") || target.startsWith("http://")) {
    return target;
  }
  if (target.startsWith("/")) {
    return `https://${domain}${target}`;
  }
  throw new Error(`Expected a full URL or absolute path, but got \`${target}\`.`);
}
