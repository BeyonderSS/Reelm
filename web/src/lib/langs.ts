// Audio languages from release names ("... BluRay Dual Audio {Hindi-English} 1080p", "Hin-Eng-Tam", "Multi Audio"), as ISO 639-1.
// Short tags (hin, tam...) only where Indian release names use them; ones that are also common words ("ben", "mar") are left out.
const WORDS: [string, string][] = [
  ["hi", "hindi|hin"], ["en", "english|eng"], ["ta", "tamil|tam"], ["te", "telugu|tel"], ["ml", "malayalam|mal"], ["kn", "kannada|kan"],
  ["bn", "bengali|bangla"], ["mr", "marathi"], ["pa", "punjabi"], ["gu", "gujarati|guj"], ["ur", "urdu"], ["ja", "japanese|jpn"], ["ko", "korean|kor"],
  ["zh", "chinese|mandarin|cantonese"], ["es", "spanish|latino|castellano"], ["fr", "french"], ["de", "german"], ["it", "italian"], ["pt", "portuguese"],
  ["ru", "russian"], ["ar", "arabic"], ["th", "thai"], ["tr", "turkish"], ["id", "indonesian"],
];
const RE = WORDS.map(([c, w]) => [c, new RegExp(`(?<![a-z])(?:${w})(?![a-z])`, "i")] as const);

/** Languages named in the text, in the order they appear (a release's first-named language is usually its first track). */
export function langs(text: string): string[] {
  const found = RE.map(([c, re]) => ({ c, at: text.search(re) })).filter((x) => x.at >= 0).sort((a, b) => a.at - b.at).map((x) => x.c);
  // Indian sites' "Dual Audio" / "Multi Audio" without names means Hindi + English
  if (/(?<![a-z])(dual|multi)[\s._-]*audio(?![a-z])/i.test(text)) for (const c of ["hi", "en"]) if (!found.includes(c) && found.length < 2) found.push(c);
  return found;
}

// ISO 639-2 (what MKV tags carry) -> 639-1
const ISO2: Record<string, string> = { hin: "hi", eng: "en", tam: "ta", tel: "te", mal: "ml", kan: "kn", ben: "bn", mar: "mr", guj: "gu", pan: "pa", urd: "ur",
  jpn: "ja", kor: "ko", spa: "es", fre: "fr", fra: "fr", ger: "de", deu: "de", ita: "it", por: "pt", rus: "ru", chi: "zh", zho: "zh", ara: "ar", tha: "th", ind: "id", tur: "tr" };
export const iso1 = (code: string) => { const c = code.toLowerCase(); return ISO2[c] ?? c; };
