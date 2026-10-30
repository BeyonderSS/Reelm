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
console.log("ok");
