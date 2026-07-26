import {
  CONFIG_FILE_PATH,
  expandPurgeTargets,
  loadConfig,
  purgeCache,
  saveConfig,
  validateCredentials,
} from "./cloudflare.js";
import { promptForConfig } from "./prompt.js";

export async function main(args) {
  const [command, ...rest] = args;

  if (!command || command === "--help" || command === "-h") {
    printHelp();
    return;
  }

  if (command === "login") {
    await runLogin(rest);
    return;
  }

  if (command === "purge") {
    await runPurge(rest);
    return;
  }

  throw new Error(`Unknown command: ${command}`);
}

async function runLogin(args) {
  const parsed = parseFlags(args);
  const config = await promptForConfig({
    apiToken: parsed.options["api-token"],
    zoneId: parsed.options["zone-id"],
    domain: parsed.options.domain,
  });

  await validateCredentials(config);
  await saveConfig(config);

  console.log(`Saved Cloudflare credentials to ${CONFIG_FILE_PATH}`);
}

async function runPurge(args) {
  const parsed = parseFlags(args);
  const everything = Boolean(parsed.options.everything);
  const config = await loadConfig();

  if (!config) {
    throw new Error("No Cloudflare login found. Run `cloudflare-cli login` first.");
  }

  if (everything && parsed.positionals.length > 0) {
    throw new Error("Use either --everything or explicit paths/URLs, not both.");
  }

  if (!everything && parsed.positionals.length === 0) {
    throw new Error("Provide one or more paths/URLs to purge, or use --everything.");
  }

  const domain = parsed.options.domain || config.domain;
  const request = everything
    ? { purge_everything: true }
    : { files: expandPurgeTargets(parsed.positionals, domain) };

  await purgeCache(config, request);

  if (everything) {
    console.log(`Purged Cloudflare cache for zone ${config.zoneId}`);
  } else {
    console.log(`Purged ${request.files.length} Cloudflare cache entr${request.files.length === 1 ? "y" : "ies"}`);
  }
}

function parseFlags(args) {
  const options = {};
  const positionals = [];

  for (let index = 0; index < args.length; index += 1) {
    const current = args[index];
    if (current === "--everything") {
      options.everything = true;
      continue;
    }
    if (current.startsWith("--")) {
      const key = current.slice(2);
      const value = args[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error(`Missing value for ${current}`);
      }
      options[key] = value;
      index += 1;
      continue;
    }
    positionals.push(current);
  }

  return { options, positionals };
}

function printHelp() {
  console.log(`cloudflare-cli

Usage:
  cloudflare-cli login [--api-token TOKEN] [--zone-id ZONE_ID] [--domain DOMAIN]
  cloudflare-cli purge --everything
  cloudflare-cli purge [--domain DOMAIN] /path https://example.com/path
`);
}
