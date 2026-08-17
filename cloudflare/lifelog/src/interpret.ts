/**
 * Mints a short-lived credential for a realtime translation session.
 *
 * The device connects to OpenAI directly — audio has to go straight there or
 * the latency that makes interpretation usable is spent on an extra hop — but
 * it must not hold the API key to do it. Every app on this device can open a
 * root shell (see the README), so a key stored on it is not a key. The Worker
 * holds the key and hands out `ek_…` secrets that expire.
 *
 * That is the whole of this module. No audio passes through here, nothing is
 * stored, and the session's content never touches this account: the Worker's
 * only job is to be the thing that knows the password.
 */

interface InterpretEnv {
  OPENAI_API_KEY?: string;
}

/** Priced by audio duration rather than tokens: $0.034 a minute, either way. */
const MODEL = "gpt-realtime-translate";

const CLIENT_SECRETS = "https://api.openai.com/v1/realtime/translations/client_secrets";

/**
 * Targets the device is allowed to ask for.
 *
 * The model takes 70-plus source languages and needs no help detecting them;
 * only the output is chosen. An allowlist rather than a passthrough because
 * this parameter arrives from a device and ends up in a request billed to this
 * account, and "whatever the client said" is not a good enough reason to spend
 * money on it.
 */
const TARGETS: Record<string, string> = {
  ja: "Japanese",
  en: "English",
  ko: "Korean",
  "zh": "Chinese",
  es: "Spanish",
  fr: "French",
  de: "German",
};

export async function mintInterpretSession(
  env: InterpretEnv,
  url: URL,
): Promise<Response> {
  if (!env.OPENAI_API_KEY) {
    return json(
      {
        error: "server missing OPENAI_API_KEY",
        hint: "wrangler secret put OPENAI_API_KEY",
      },
      500,
    );
  }

  const target = (url.searchParams.get("target") ?? "ja").toLowerCase();
  if (!(target in TARGETS)) {
    return json({ error: "unsupported target", supported: Object.keys(TARGETS) }, 400);
  }

  let response: Response;
  try {
    response = await fetch(CLIENT_SECRETS, {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.OPENAI_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        session: {
          model: MODEL,
          audio: {
            input: {
              // far_field, because of where this device sits. The choice is
              // between a headset microphone and a room one, and the whole
              // point of this feature is a device on a table between two
              // people — near_field would be tuned for the case that cannot
              // happen here.
              noise_reduction: { type: "far_field" },
              transcription: { model: "gpt-realtime-whisper" },
            },
            output: { language: target },
          },
        },
      }),
    });
  } catch (error) {
    return json({ error: "could not reach OpenAI", detail: String(error).slice(0, 200) }, 502);
  }

  const body = await response.text();
  if (!response.ok) {
    // Passed through rather than flattened. A 401 here means the key, a 400
    // means this request shape has drifted from the API, and a device that is
    // told only "failed" cannot tell those apart from a screen.
    return json({ error: "OpenAI refused", status: response.status, detail: body.slice(0, 400) }, 502);
  }

  let parsed: any;
  try {
    parsed = JSON.parse(body);
  } catch {
    return json({ error: "OpenAI returned unparseable JSON" }, 502);
  }

  // The device needs the secret and the URL to use it against, and nothing
  // else. Returning the whole upstream body would hand it fields it has no
  // business holding.
  return json({
    client_secret: parsed?.value ?? parsed?.client_secret?.value ?? null,
    expires_at: parsed?.expires_at ?? null,
    model: MODEL,
    target,
    target_name: TARGETS[target],
    url: `wss://api.openai.com/v1/realtime/translations?model=${MODEL}`,
  });
}

/** The set the device offers in its settings; kept here so there is one list. */
export function interpretTargets(): Response {
  return json({ targets: Object.entries(TARGETS).map(([code, name]) => ({ code, name })) });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
