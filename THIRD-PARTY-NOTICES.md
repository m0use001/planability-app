# Third-Party Notices

This project includes third-party software and fonts. Each item keeps its own
license; full license texts live in [`docs/licenses/`](docs/licenses/).

## Bundled fonts (`app/src/main/res/font/`)

All bundled Chinese/Latin fonts are under the **SIL Open Font License 1.1**.
The shipped files are **subsets** of the originals (glyph-subsetted for size);
the name-table license records are stripped by subsetting, so the original
licenses below apply. Each font's license is reproduced or linked here, as the
OFL requires the license to accompany redistribution.

| File | Font | Upstream | License |
|---|---|---|---|
| `googlesansflex.ttf` | Google Sans Flex (subset) | github.com/google/fonts | OFL-1.1 |
| `notosanssc.ttf` | Noto Sans SC (subset) | github.com/notofonts/noto-cjk | OFL-1.1 |
| `rhr.otf` | Resource Han Rounded CN VF (subset) | github.com/CyanoHao/Resource-Han-Rounded | OFL-1.1 (repo ships `OFL-License.txt`) |
| `wenyuanrounded.ttf` | WenYuan Rounded SC VF (subset) | github.com/takushun-wu/WenYuanFonts | OFL-1.1, Reserved Font Name "WenYuan Rounded"/"文源圆体" |
| `yuan975_400.ttf`, `yuan975_700.ttf` | LXGW 975 Yuan SC (subset) | github.com/lxgw/975Yuan | OFL-1.1 with author's additional permission on Reserved Font Names — see `docs/licenses/OFL-1.1.txt` for the full notice |

## jlatexmath (`ru.noties:jlatexmath-android`)

LaTeX rendering. License: **GPL-2.0-or-later with a linking exception** that
permits linking with independently-licensed modules and distributing the
resulting executable under terms of your choice. The library is used as an
unmodified Maven dependency.

- License notice: [`docs/licenses/jlatexmath-LICENSE.txt`](docs/licenses/jlatexmath-LICENSE.txt)
- GPL-2.0 text: [`docs/licenses/jlatexmath-COPYING.txt`](docs/licenses/jlatexmath-COPYING.txt)
- Source: github.com/noties/jlatexmath-android (port), github.com/opencollab/jlatexmath (upstream)
- The library bundles its own fonts (OFL / public domain / Knuth license /
  GPL-2 greek fonts) — see the license notice's font section.

## Syncthing native library (`app/src/main/jniLibs/arm64-v8a/libsyncthingnative.so`)

- License: **MPL-2.0** — [`docs/licenses/MPL-2.0.txt`](docs/licenses/MPL-2.0.txt)
- Source: github.com/syncthing/syncthing (packaged for Android by the
  syncthing-android community fork)

## mermaid (`app/src/main/assets/mermaid.min.js`)

- License: MIT — github.com/mermaid-js/mermaid

## Vendored assets (`app/src/main/assets/`)

| Asset | Source | License |
|---|---|---|
| `leaflet/leaflet.js`, `leaflet/leaflet.css` (v1.9.4) | github.com/Leaflet/Leaflet | BSD-2-Clause — Copyright (c) 2010-2024, Volodymyr Agafonkin; Copyright (c) 2010-2011, CloudMade. Used by MapPicker. |
| `mammoth.browser.min.js` (1.13.0) | github.com/mwilliamson/mammoth.js | BSD-2-Clause — Copyright (c) 2013–present, Michael Williamson. Used by docx_preview.html. |
| `bge_vocab.txt`, `bge_vocab_en.txt` | BAAI bge-small-zh-v1.5 / bge-small-en-v1.5 tokenizer (via hf-mirror.com/Xenova) | MIT — Copyright (c) 2023, BAAI. Derived tokenizer vocab. |

## Maven dependencies (Android)

All resolve from Maven Central / Google Maven at build time (nothing vendored
unless listed above):

| Dependency | License |
|---|---|
| AndroidX (core, compose, room, glance, biometric, lifecycle, test) | Apache-2.0 |
| Kotlin stdlib / coroutines | Apache-2.0 |
| okhttp | Apache-2.0 |
| Apache POI (poi, poi-ooxml, poi-scratchpad) | Apache-2.0 |
| pdfbox-android (com.tom-roush) | Apache-2.0 |
| vosk-android | Apache-2.0 |
| jlatexmath-android | GPL-2.0-or-later + linking exception (see above) |
| org.json | platform android.jar class (not redistributed; JSON license N/A) |
| ML Kit text-recognition-chinese | Google ML Kit Terms of Service (proprietary runtime) |
| geniex-android (Qualcomm) | BSD-3-Clause + Qualcomm Terms of Use |
| Other permissive runtime deps (onnxruntime-android, BouncyCastle bcprov/bcpkix/bcutil, JNA, osmdroid, Apache commons-{compress,collections4,math3,codec,io}, xmlbeans, log4j-api, curvesapi, SparseBitSet, jspecify) | MIT / Apache-2.0 / Bouncy Castle Licence (MIT variant) / BSD-3-Clause — per-POM verified 2026-11 |

## npm dependencies (desktop)

| Dependency | License |
|---|---|
| react, react-dom | MIT |
| @material/web | Apache-2.0 |
| @lit/react | BSD-3-Clause |
| better-sqlite3 | MIT |
| docx | MIT |
| exceljs | MIT |
| jszip | (MIT OR GPL-3.0-or-later) — MIT path taken |
| lucide-react | ISC |
| marked | MIT |
| pdfjs-dist | Apache-2.0 |
| pptxgenjs | MIT |
| @modelcontextprotocol/sdk | MIT |

| Transitive non-standard-license packages: sax (BlueOak-1.0.0), buffers / chainsaw (substack, MIT) | per license-checker 2026-11 |

Full transitive dependency licenses can be generated with
`npm ls --all --json` (desktop) and `./gradlew :app:dependencies` (Android).
