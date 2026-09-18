import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // feed.ts は Workers の API を使わない純粋な関数の集まりなので、
    // workerd を起こす必要がない。node で足りる。
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
