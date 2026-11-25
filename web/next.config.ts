import type { NextConfig } from "next";
// Short links people type on a TV (Downloader) or phone: 302, so they always fetch the latest build
const DL = "https://reelm-api.ssbeyonder.workers.dev/dl";
export default {
  images: { unoptimized: true },
  redirects: async () => [{ source: "/tv", destination: `${DL}/Reelm-TV.apk`, statusCode: 302 }, { source: "/app", destination: `${DL}/Reelm.apk`, statusCode: 302 }],
} satisfies NextConfig;
