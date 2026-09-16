/** Offline integration harness: production handlers, local D1/R2/Queue, fake AI. */
import worker from "../src/index";
const withFakeAi = (env: any) => ({ ...env, INGEST_TOKEN: "desktop-test-ingest", ADMIN_TOKEN: "desktop-test-admin",
  AI: { run: async () => ({ response: "ローカル検証用のPC画面です。" }) } });
export default {
  fetch(request: Request, env: any) {
    if (!["localhost", "127.0.0.1", "[::1]"].includes(new URL(request.url).hostname)) return new Response("Local test only", { status: 403 });
    return worker.fetch(request, withFakeAi(env));
  },
  queue(batch: any, env: any) { return worker.queue(batch, withFakeAi(env)); },
};
