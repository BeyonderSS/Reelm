import assert from "node:assert/strict";
import { langs, iso1 } from "./langs.ts";
const t: [string, string[]][] = [
  ["Spider-Man: No Way Home (2021) BluRay Dual Audio {Hindi-English} 1080p", ["hi", "en"]],
  ["Dual Audio [Hindi ORG 5.1ch – English]", ["hi", "en"]],
  ["Doctor.Strange.2022.1080p.Hin-Eng.DDP5.1.x264.mkv", ["hi", "en"]],
  ["Movie_2022_Hindi_English_720p.mkv", ["hi", "en"]],
  ["Some Movie (2022) Dual Audio 720p", ["hi", "en"]],
  ["Some Movie (2022) Hindi Dubbed 720p", ["hi"]],
  ["Movie 2022 Multi Audio [Hindi + English + Tamil + Telugu] 1080p", ["hi", "en", "ta", "te"]],
  ["Dual Audio [Tamil-Telugu]", ["ta", "te"]],
  ["Doctor Strange in the Multiverse of Madness 2022 2160p WEB-DL DDP5.1 Atmos HEVC", []],
  ["Ben-Hur 1959 1080p Malena Telly Kanye Hindsight English", ["en"]],
  ["Hindi", ["hi"]], ["Original", []], ["Spanish (Latin America)", ["es"]],
];
for (const [s, want] of t) assert.deepEqual(langs(s), want, s);
assert.equal(iso1("hin"), "hi"); assert.equal(iso1("ENG"), "en"); assert.equal(iso1("hi"), "hi");
console.log("langs ok");
