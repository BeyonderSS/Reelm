import assert from "node:assert/strict";
import { rank } from "./search-rank.ts";
const mb = (id: string, title: string, year: number, genres: string[]) => ({ id: `mb:${id}`, title, year, genres });
const MB = [
  mb("1", "Gangs of Wasseypur [Hindi]", 2012, ["Action", "Comedy", "Crime"]), mb("2", "Gang Rape", 2013, ["Crime"]), mb("3", "Gang Bang", 2014, ["Erotic", "Hot", "Romance"]),
  mb("4", "Gang Land", 2010, ["Crime"]), mb("5", "WWE Gang Wrestling", 2020, ["Wrestling"]), mb("6", "Gang Pop Hits", 2020, ["Pop"]), mb("7", "Gang Boss", 2024, ["Modern Drama"]),
];
const TM = [{ id: "tmdb:movie:117691", title: "Gangs of Wasseypur - Part 1", year: 2012 }, { id: "tmdb:movie:126400", title: "Gangs of Wasseypur - Part 2", year: 2012 }];
const CM = [{ id: "tt1954470", title: "Gangs of Wasseypur", year: 2012 }];
const ids = (q: string, mature = false, tm = TM) => rank(q, { mb: MB, tm, cm: CM }, mature).map((i) => i.id);
assert.deepEqual(ids("gang of wasepur"), ["mb:1", "tmdb:movie:126400"]); // typo ranked first; Part 1 x3 deduped to the MovieBox item
const g = (m: boolean) => ids("gang bang", m);
assert.ok(!g(false).includes("mb:3") && g(true).includes("mb:3"));
assert.ok(!ids("gang rape").includes("mb:2") && ids("gang rape", true).includes("mb:2")); // explicit title, ordinary genre
assert.ok(!ids("gang").some((i) => ["mb:5", "mb:6", "mb:7"].includes(i)));
assert.ok(!ids("gang of wasepur").includes("mb:4")); // Gang Land scores < 0.6

// spacing, typos and popularity: catalogues shaped like what MovieBox / Cinemeta return for these queries
const VQ = [mb("v1", "Vision Quest", 1985, ["Drama"]), mb("v2", "Vision Quest", 2025, ["Drama"]), mb("v3", "Vernacular Visions 9 Feat. Guest Presenting Photographer Justin Smith", 2014, ["Documentary"]),
  { ...mb("v4", "VisionQuest [Hindi]", 2026, ["Action", "Sci-Fi"]), rating: 8.3 }, mb("v5", "VisionQuest Sound", 2015, ["Music"])];
VQ[0] = { ...VQ[0], rating: 6.7 };
const first = (q: string, l: any[]) => rank(q, { mb: l, tm: [], cm: [] }, false)[0]?.title;
for (const q of ["vision quest", "visionquest", "visoin quest", "Vision-Quest"]) assert.equal(first(q, VQ), "VisionQuest [Hindi]", q);
const BB = [mb("b1", "Ismo: Breaking Bad English", 2024, ["Comedy"]), mb("b2", "The Bad News Bears in Breaking Training", 1977, ["Comedy"]), { ...mb("b3", "Breaking Bad [Hindi] S1-S5", 2013, ["Crime", "Drama"]), rating: 9.5 }];
assert.equal(first("brekaing bad", BB), "Breaking Bad [Hindi] S1-S5");
const IN = [mb("i1", "The Science of Interstellar", 2014, ["Documentary"]), mb("i2", "Interstellar Wars", 2016, ["Sci-Fi"]), { ...mb("i3", "Interstellar [Hindi]", 2014, ["Sci-Fi"]), rating: 8.7 }];
assert.equal(first("intersteller", IN), "Interstellar [Hindi]");
const NS = [mb("n1", "Naruto Shippuden the Movie: Blood Prison", 2011, ["Animation"]), { ...mb("n2", "Naruto: Shippuden [Hindi]", 2017, ["Animation"]), rating: 8.7 }];
assert.equal(first("naruto shipuden", NS), "Naruto: Shippuden [Hindi]");
const SV = [mb("s1", "Hum Suryavasham Hain", 2022, ["Drama"]), { ...mb("s2", "Sooryavansham [Hindi]", 1999, ["Action", "Drama"]), rating: 6.4 }, mb("s3", "Suryavanshi", 1992, ["Action"])];
assert.ok(rank("suryavansam", { mb: SV, tm: [], cm: [] }, false).some((i) => i.title === "Sooryavansham [Hindi]"));
console.log("ok");
