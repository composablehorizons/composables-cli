import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";

export async function promptForConfig(initialValues = {}) {
  const rl = createInterface({ input, output });

  try {
    const apiToken = await promptValue(rl, "Cloudflare API token", initialValues.apiToken);
    const zoneId = await promptValue(rl, "Cloudflare zone id", initialValues.zoneId);
    const domain = await promptValue(rl, "Default domain", initialValues.domain);

    return {
      apiToken,
      zoneId,
      domain,
    };
  } finally {
    rl.close();
  }
}

async function promptValue(rl, label, defaultValue = "") {
  const suffix = defaultValue ? ` [${defaultValue}]` : "";
  const answer = (await rl.question(`${label}${suffix}: `)).trim();
  return answer || defaultValue;
}
