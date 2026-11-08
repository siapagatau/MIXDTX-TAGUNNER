# PROMPT_SKIN_MIXER.md — Aturan Membuat Skin APK DTX Mixer

Dokumen ini MANDIRI. AI yang menerima dokumen ini + **gambar referensi** + **file skin DTX (contoh format)** harus bisa membuat skin yang
kompatibel dengan Skin Engine APK **DTX Mixer** (audio mixer mic: mic → reverb, echo, EQ, gate → speaker, plus EQ audio global).
Bahasa: Indonesia. Jangan menebak fungsi di luar katalog. **Jangan boros langkah**: kerjakan satu skin per gambar, langsung hasil akhir.

## 1. Konsep (sama dengan skin DTX)
SKIN = TAMPILAN + LAYOUT + CONTROLLER + AREA SENTUH + ID KONTROL. Seluruh skin di-STRETCH memenuhi layar (satu gambar utuh).
- `skin.svg`: gambar + posisi + ID + hit area + state visual. **Tanpa logika** (tanpa script/onclick).
- `skin.json`: daftar kontrol + mapping ke fungsi APK.
- Pad DTX diganti kontrol mixer: **toggle (saklar), rotary (knob), slider (fader), lampu meter, display, tombol**. Tidak ada `pad-N` / `sample.play`.
- Gaya visual: **ikuti skin DTX yang dilampirkan** (bahan, bevel, warna, tipografi, kualitas detail). Hasil harus terlihat seperti produk DTX, bukan UI generik.
- Layout: **ikuti gambar referensi** (posisi, ukuran, urutan kontrol). Kontrol yang tidak ada di gambar tidak perlu dibuat.

## 2. Output per skin (folder `skins/<id>/`)
```
skin.svg    skin.json    preview.svg
```
Repo `MIXDTX-SKIN`: `README.md  index.json  PROMPT_SKIN_MIXER.md  skins/<id>/...`. `id` hanya `A-Z a-z 0-9 _ -` (maks 64). Tanpa kode APK di repo.
`index.json` = format yang sama dengan repo skin DTX (sha256 + size SEMUA file, dihitung SETELAH file final). `padMin`/`padMax` isi `0`.
Repo diambil dari: `https://github.com/siapagatau/MIXDTX-SKIN` (branch master, `index.json` di root).

## 3. Aturan skin.svg
### 3.1 Root (WAJIB)
```xml
<svg xmlns="http://www.w3.org/2000/svg" data-skin-name="Nama" data-skin-version="1.0" data-skin-author="Author"
     width="1280" height="600" viewBox="0 0 1280 600">
```
Rasio disarankan landscape (mis. 1280×600). Semua koordinat memakai viewBox.
### 3.2 Larangan (skin DITOLAK)
`<script> <foreignObject> <image> <use> <audio> <video> <iframe>`, atribut `on*`, `href`/`xlink:href`, URL `http(s)://` di atribut (kecuali `xmlns`),
aset eksternal, > 20.000 elemen, file > 4 MB. Hindari `<metadata>`. Renderer = **AndroidSVG: TANPA `<filter>`**, `letter-spacing` diabaikan.
Gradien, pattern, path, text, transform aman. Font generik (`sans-serif`, `monospace`). Tekstur/bevel dibuat dengan gradien + path (bukan filter/gambar).
### 3.3 Layer disarankan
`layer-bg` → `layer-deco` → `layer-controls` → `layer-hit` (paling atas, hanya rect transparan).
### 3.4 Hit area
`<rect id="hit-<id kontrol>" x y width height fill="#000" fill-opacity="0"/>` di `layer-hit`, **seluruhnya di dalam viewBox**. Wajib untuk tombol, toggle, knob, slider.
Jangan saling menimpa (hit terkecil menang, tapi beri peringatan). Target sentuh nyaman ≥ 40 unit pada layar landscape.
### 3.5 Toggle (saklar ON/OFF)
```xml
<g id="tg-mic" data-type="toggle">
  <g id="tg-mic-normal"> ...tampilan OFF... </g>
  <g id="tg-mic-active" display="none"> ...tampilan ON (menyala)... </g>
</g>
```
Tap membalik status; APK menampilkan `-active` saat ON dan `-normal` saat OFF. Nama state di skin.json: `normal` dan `active`.
### 3.6 Knob (rotary)
```xml
<g id="knob-reverb-wet" data-type="rotary" data-cx="300" data-cy="220" data-angle-min="-135" data-angle-max="135" data-value-default="0.35"> ...gambar knob + penanda... </g>
```
`data-cx/cy` = pusat putar (WAJIB). Gambar knob menghadap ke atas pada nilai tengah; APK memutar seluruh grup: sudut = min + (max−min)×nilai. Geser jari = ubah nilai.
Gambar jalur/skala/label knob DI LUAR grup (statis). Grup hanya berisi bagian yang ikut berputar.
### 3.7 Slider (fader lurus)
```xml
<g id="sl-eqaudio-gain" data-type="slider" data-x1="962" data-y1="520" data-x2="962" data-y2="190" data-value-default="0.5">
  <rect x="939" y="508" width="46" height="24" fill="none"/>     <!-- WAJIB rect PERTAMA = kotak handle, pusat = posisi nilai 0 -->
  ...gambar handle (cap fader)...
</g>
```
`(x1,y1)` = pusat handle pada nilai 0, `(x2,y2)` = pusat handle pada nilai 1 (bisa vertikal atau horizontal). Grup berisi **HANDLE saja**;
jalur, skala, dan label digambar statis di luar grup. Hit area = persegi panjang yang membungkus seluruh jalur.
### 3.8 Lampu meter (LED level suara)
Gambar lampu MATI di dasar (redup) dan lampu MENYALA sebagai `<g id="led-mic-3-on" display="none"><rect .../></g>` (**`<rect>` pertama = area lampu**, boleh dengan halo).
Daftarkan di skin.json level atas (`indicators`). Lampu menyala bila level ≥ `level`. Contoh bar 12 LED: level = (i−0.5)/12 → 0.042, 0.125, 0.208, … Warna: hijau → kuning → merah di LED atas.
### 3.9 Display (LCD teks)
`<g id="slot-display" data-type="display">` — **`<rect>` pertama di dalamnya = area LCD**. Teks digambar APK dari template di skin.json. Tanpa hit area.
### 3.10 Tombol aksi (opsional)
`<g id="btn-theme" data-type="button">` + `btn-theme-normal` / `btn-theme-active` (opsional; tanpa state = highlight putih transparan saat ditekan) + `hit-btn-theme`.
### 3.11 Dekorasi
Logo, sekrup, speaker, panel, label, garis skala = dekorasi statis (tanpa hit, tanpa mapping). **Jangan membuat tombol palsu.** Catat di `unsupportedInReference`.

## 4. Katalog fungsi RESMI (hanya ini boleh di skin.json; di luar katalog = skin ditolak)
| function | tipe kontrol | keterangan |
|---|---|---|
| `mic.toggle` | toggle | nyalakan/matikan mic → speaker |
| `reverb.toggle` `echo.toggle` `eqmic.toggle` `eqaudio.toggle` | toggle | on/off efek (eqaudio = EQ untuk suara semua app) |
| `mic.volume` `mic.gate` | rotary / slider | volume mic, noise gate |
| `reverb.wet` `reverb.dry` `reverb.width` `reverb.damp` `reverb.room` `reverb.cess` | rotary / slider | parameter reverb |
| `echo.delay` `echo.feedback` `echo.mix` | rotary / slider | parameter echo |
| `eqmic.low` `eqmic.mid` `eqmic.high` | rotary / slider | EQ mic 3 band (0.5 = flat) |
| `eqaudio.gain` `eqaudio.sub` `eqaudio.low` `eqaudio.mid` `eqaudio.high` | rotary / slider | EQ audio global (0.5 = flat) |
| `floating.toggle` | button | panel mic melayang di atas app lain |
| `theme.open` | button | dialog tema (warna, background, visual sentuhan) |
| `skin.pick` | button | pemilih skin |
| `sysreverb.toggle` `sysreverb.reverb.toggle` `sysreverb.echo.toggle` | toggle | REVERB AUDIO: nyalakan/matikan capture suara app lain; on/off reverb; on/off delay |
| `sysreverb.wet` `.room` `.damp` `.width` `.cess` `.predelay` | rotary / slider | REVERB AUDIO: parameter reverb |
| `sysreverb.delay` `.feedback` `.mix` `.gain` | rotary / slider | REVERB AUDIO: delay + level keluaran efek |
| `sysreverb.rsend.sub` `.low` `.mid` `.high` / `sysreverb.esend.sub` `.low` `.mid` `.high` | rotary / slider | REVERB AUDIO: kirim per band ke reverb (`rsend`) dan ke delay (`esend`) |
| `eq31.mode` | toggle | MODE EQUALIZER: tampilkan bagian equalizer menyambung di bawah skin mixer (opsional di skin mixer) |
| `eq31.toggle` `eq31.reset` | toggle / button | EQ 31 band ON/OFF; semua band kembali flat. Kontrol band 31 (`eq31.l.NN`/`eq31.r.NN`) dibuat di skin EQUALIZER, lihat `PROMPT_SKIN_EQUALIZER.md` |
| `meter.mic` `meter.output` `meter.sysreverb` | indicator (`meter`) | level mic / level keluaran / level REVERB AUDIO, hanya untuk `indicators` |
| `display.status` | display | teks status dari template |

Satu function boleh dipakai satu kontrol saja per skin. Kontrol boleh berupa knob ATAU slider untuk function bernilai (pilih sesuai gambar).
Fungsi yang tidak diberi kontrol di gambar = tidak bisa diakses di skin itu (tidak ada menu tambahan).

## 5. Aturan skin.json
```json
{
  "schemaVersion": 1, "name": "Nama", "version": "1.0", "author": "Author",
  "svg": "skin.svg", "viewBox": "0 0 1280 600",
  "layout": { "padZone": "0 0 1280 600" },
  "controls": [
    { "id": "slot-display", "type": "display", "lines": 2 },
    { "id": "tg-mic", "type": "toggle", "hit": "hit-tg-mic", "states": { "normal": "tg-mic-normal", "active": "tg-mic-active" }, "trigger": "tap" },
    { "id": "knob-mic-volume", "type": "rotary", "hit": "hit-knob-mic-volume", "angleMin": -135, "angleMax": 135, "defaultValue": 0.5 },
    { "id": "sl-eqaudio-gain", "type": "slider", "hit": "hit-sl-eqaudio-gain", "defaultValue": 0.5 },
    { "id": "btn-theme", "type": "button", "hit": "hit-btn-theme", "trigger": "tap" }
  ],
  "mapping": {
    "slot-display": { "function": "display.status", "template": ["MIC {mic:ONOFF}  REV {reverb:ONOFF}", "ECHO {echo:ONOFF}  EQ {eqmic:ONOFF}"], "textColor": "#59D185" },
    "tg-mic": { "function": "mic.toggle" },
    "knob-mic-volume": { "function": "mic.volume" },
    "sl-eqaudio-gain": { "function": "eqaudio.gain" },
    "btn-theme": { "function": "theme.open" }
  },
  "indicators": [ { "id": "led-mic-1", "meter": "meter.mic", "level": 0.042, "on": "led-mic-1-on" } ],
  "unsupportedInReference": [ { "id": "deco-logo", "kind": "decorative", "label": "Logo", "reason": "Dekorasi" } ]
}
```
- `layout.padZone` = persegi bagian skin yang ditampilkan (biasanya seluruh viewBox). `panelZone` TIDAK dipakai di mixer.
- Tipe valid: `toggle | rotary | slider | button | display`. Setiap kontrol WAJIB punya entri `mapping` dengan function yang cocok dengan tipenya.
- Template display: `{mic:ONOFF}` = status saklar (`mic`, `reverb`, `echo`, `eqmic`, `eqaudio`); `{reverb.wet:PCT}` = nilai 0–100. Token lain dibiarkan apa adanya.
- Semua `id`, `hit`, state harus ada di SVG; tidak ada ID ganda; `svg` hanya nama file biasa.
- `defaultValue` di JSON / `data-value-default` di SVG: pakai nilai awal APK (mic.volume 0.5, mic.gate 0.5, reverb.wet 0.35, reverb.dry 0.85, reverb.width 1.0, reverb.damp 0.3, reverb.room 0.6, reverb.cess 0.6, echo.delay 0.45, echo.feedback 0.45, echo.mix 0.3, semua EQ 0.5).

## 6. Alur kerja (hemat)
1. Lihat gambar referensi + skin DTX contoh → tulis daftar kontrol singkat (toggle/knob/slider/LED/display/tombol + function-nya) di `analysis/<id>.md` (maks 1 halaman).
2. Tulis `skin.svg` (gaya DTX, tanpa filter) → `skin.json` → `preview.svg` (skin + kotak hit area berlabel ID).
3. Validasi dengan skrip sederhana (python): XML valid, tanpa elemen terlarang, semua ID/hit/state ada, hit di dalam viewBox, function ada di katalog dan cocok tipe.
4. Hitung sha256+size → `index.json`.
Jika berhenti di tengah, tulis `CHECKPOINT.md` (tahap selesai, file dibuat, keputusan, langkah berikut). Jangan menyatakan selesai tanpa file nyata.

## 7. Checklist akhir
Visual mengikuti gambar dan setara kualitas skin DTX · tidak ada `<filter>/<image>/<use>/<script>` · root punya `data-skin-name/version/author` + `viewBox` ·
semua toggle punya state normal+active · knob punya `data-cx/cy` · slider punya `data-x1..y2` + rect handle pertama · semua kontrol punya `hit-*` di dalam viewBox ·
semua function ada di katalog · `indicators` valid · `index.json` sha256/size cocok.
